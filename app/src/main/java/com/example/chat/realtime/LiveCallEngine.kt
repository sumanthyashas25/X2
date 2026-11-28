package com.example.chat.realtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.BuildConfig
import com.example.data.FirebaseStartup
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Real-time audio & video engine for X.MAX calling:
 * - Full-duplex 8 kHz G.711 mu-law compressed PCM streaming over RTDB
 * - Hardware AEC, NoiseSuppressor, speaker/earpiece routing & mute isolation
 * - Guaranteed listener & hardware cleanup on hangup (zero ghost audio)
 * - Low-latency CameraX 180p frame capture + background-thread Base64/Bitmap decode
 */
object LiveCallEngine {
    private const val TAG = "LiveCallEngine"
    private const val SAMPLE_RATE = 8000
    private const val AUDIO_PACKET_SAMPLES = 800 // 100ms per packet -> 800 bytes mu-law (~8 KB/s)
    private const val FRAME_INTERVAL_MS = 160L
    private const val JPEG_QUALITY = 38

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Ringtone / ringback state
    private var toneGenerator: ToneGenerator? = null
    private var incomingRingtone: Ringtone? = null

    // Hardware audio state
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var audioCaptureJob: Job? = null
    private var audioPlaybackJob: Job? = null

    // RTDB listener tracking (always removed cleanly in endCallAudio)
    private var peerAudioRef: DatabaseReference? = null
    private var peerAudioListener: ValueEventListener? = null
    private val activeAudioCallId = AtomicBoolean(false)
    @Volatile private var currentCallId: String = ""
    @Volatile private var isLocalMicMuted: Boolean = false
    private val outgoingAudioSeq = AtomicLong(0L)
    @Volatile private var lastPlayedPeerSeq: Long = -1L

    private var inboundPcmChannel = Channel<ShortArray>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _localVoiceAmplitude = MutableStateFlow(0f)
    val localVoiceAmplitude: StateFlow<Float> = _localVoiceAmplitude.asStateFlow()

    private val _remoteVoiceAmplitude = MutableStateFlow(0f)
    val remoteVoiceAmplitude: StateFlow<Float> = _remoteVoiceAmplitude.asStateFlow()

    private val _peerLastMediaAtMs = MutableStateFlow(0L)
    val peerLastMediaAtMs: StateFlow<Long> = _peerLastMediaAtMs.asStateFlow()

    private val _peerAudioMuted = MutableStateFlow(false)
    val peerAudioMuted: StateFlow<Boolean> = _peerAudioMuted.asStateFlow()

    // Video state
    private val frameAnalysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var lastFrameSendTime = 0L

    private fun resolveDb(context: Context? = null): FirebaseDatabase? {
        FirebaseStartup.ensureInitialized(context)
        return runCatching {
            val options = FirebaseApp.getInstance().options
            val url = RtdbPresenceManager.resolveRtdbUrl(
                configuredUrl = BuildConfig.FIREBASE_RTDB_URL,
                activeProjectId = options.projectId,
                optionsDatabaseUrl = options.databaseUrl
            )
            FirebaseDatabase.getInstance(url)
        }.onFailure {
            Log.d(TAG, "RTDB not available for call streaming: ${it.message}")
        }.getOrNull()
    }

    private fun callRef(companyId: String, callId: String, context: Context? = null): DatabaseReference? {
        if (callId.isBlank()) return null
        val safeCompany = companyId.trim().ifBlank { "xmax" }
        return resolveDb(context)?.reference
            ?.child("companies")
            ?.child(safeCompany)
            ?.child("calls")
            ?.child(callId.trim())
    }

    // ==========================================
    // AUDIO & HARDWARE ROUTING
    // ==========================================

    fun startOutgoingRingback() {
        stopRinging()
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 75)
            tg.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            toneGenerator = tg
        }
    }

    fun startIncomingRingtone(context: Context) {
        stopRinging()
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            ringtone.play()
            incomingRingtone = ringtone
        }
    }

    fun stopRinging() {
        runCatching {
            toneGenerator?.stopTone()
            toneGenerator?.release()
        }
        toneGenerator = null

        runCatching {
            incomingRingtone?.stop()
        }
        incomingRingtone = null
    }

    /**
     * Initializes real hardware audio routing and full-duplex PCM streaming over RTDB.
     * Safe to invoke idempotently for the same callId.
     */
    fun startInCallAudio(
        context: Context,
        companyId: String,
        callId: String,
        myUid: String,
        peerUid: String,
        isVideoCall: Boolean
    ) {
        if (callId.isBlank() || myUid.isBlank()) return
        if (activeAudioCallId.get() && currentCallId == callId) {
            return
        }
        cleanupAudioResourcesOnly()
        stopRinging()

        currentCallId = callId
        activeAudioCallId.set(true)
        isLocalMicMuted = false
        outgoingAudioSeq.set(0L)
        lastPlayedPeerSeq = -1L
        _peerLastMediaAtMs.value = System.currentTimeMillis()
        _peerAudioMuted.value = false
        inboundPcmChannel = Channel(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        runCatching {
            am?.mode = AudioManager.MODE_IN_COMMUNICATION
            setSpeakerphone(context, isVideoCall)
        }

        // 1. Initialize AudioTrack for real-time peer voice playback
        val channelOut = AndroidAudioFormat.CHANNEL_OUT_MONO
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val minTrackBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, channelOut, encoding).coerceAtLeast(AUDIO_PACKET_SAMPLES * 4)

        runCatching {
            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AndroidAudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(channelOut)
                            .build()
                    )
                    .setBufferSizeInBytes(minTrackBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    channelOut,
                    encoding,
                    minTrackBuf,
                    AudioTrack.MODE_STREAM
                )
            }
            track.play()
            audioTrack = track
        }

        // Dedicated non-blocking playback worker
        audioPlaybackJob = scope.launch(Dispatchers.IO) {
            for (pcmSamples in inboundPcmChannel) {
                if (!activeAudioCallId.get()) break
                val track = audioTrack ?: continue
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    runCatching {
                        track.write(pcmSamples, 0, pcmSamples.size)
                    }
                }
            }
        }

        // 2. Initialize AudioRecord for real microphone capture with AEC + NS
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val channelIn = AndroidAudioFormat.CHANNEL_IN_MONO
            val minRecBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelIn, encoding).coerceAtLeast(AUDIO_PACKET_SAMPLES * 4)
            runCatching {
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    channelIn,
                    encoding,
                    minRecBuf
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    val sessionId = record.audioSessionId
                    if (AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                    }
                    record.startRecording()
                    audioRecord = record

                    audioCaptureJob = scope.launch(Dispatchers.IO) {
                        val packetBuffer = ShortArray(AUDIO_PACKET_SAMPLES)
                        var filled = 0
                        val tempRead = ShortArray(400)
                        var lastSilenceHeartbeat = 0L

                        while (isActive && activeAudioCallId.get() && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            val readCount = audioRecord?.read(tempRead, 0, tempRead.size) ?: 0
                            if (readCount <= 0) {
                                delay(10L)
                                continue
                            }
                            var idx = 0
                            while (idx < readCount) {
                                val space = packetBuffer.size - filled
                                val take = minOf(space, readCount - idx)
                                System.arraycopy(tempRead, idx, packetBuffer, filled, take)
                                filled += take
                                idx += take

                                if (filled == packetBuffer.size) {
                                    filled = 0
                                    if (isLocalMicMuted) {
                                        _localVoiceAmplitude.value = 0f
                                        val now = System.currentTimeMillis()
                                        if (now - lastSilenceHeartbeat >= 1200L) {
                                            lastSilenceHeartbeat = now
                                            publishAudioPacket(companyId, callId, myUid, 0f, "", muted = true, context = context)
                                        }
                                    } else {
                                        var sumSq = 0.0
                                        for (s in packetBuffer) {
                                            sumSq += (s.toDouble() * s.toDouble())
                                        }
                                        val rms = sqrt(sumSq / packetBuffer.size)
                                        val normalized = (rms / 20000.0).toFloat().coerceIn(0f, 1f)
                                        _localVoiceAmplitude.value = normalized

                                        val encodedPcm = encodeMuLawBase64(packetBuffer)
                                        publishAudioPacket(companyId, callId, myUid, normalized, encodedPcm, muted = false, context = context)
                                    }
                                }
                            }
                        }
                    }
                }
            }.onFailure { err ->
                Log.e(TAG, "Failed to start AudioRecord: ${err.message}")
            }
        }

        // 3. Subscribe to peer's live audio packets from RTDB
        if (peerUid.isNotBlank()) {
            subscribePeerAudioState(companyId, callId, peerUid, context)
        }
    }

    private fun publishAudioPacket(
        companyId: String,
        callId: String,
        myUid: String,
        amplitude: Float,
        pcmBase64: String,
        muted: Boolean,
        context: Context?
    ) {
        if (!activeAudioCallId.get() || currentCallId != callId) return
        val ref = callRef(companyId, callId, context)?.child("audio")?.child(myUid) ?: return
        val seq = outgoingAudioSeq.incrementAndGet()
        runCatching {
            ref.onDisconnect().removeValue()
            ref.setValue(
                mapOf(
                    "seq" to seq,
                    "amp" to amplitude,
                    "pcm" to pcmBase64,
                    "muted" to muted,
                    "ts" to ServerValue.TIMESTAMP
                )
            )
        }
    }

    private fun subscribePeerAudioState(
        companyId: String,
        callId: String,
        peerUid: String,
        context: Context?
    ) {
        removePeerAudioListener()
        val ref = callRef(companyId, callId, context)?.child("audio")?.child(peerUid) ?: return
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!activeAudioCallId.get() || currentCallId != callId) return
                if (!snapshot.exists()) return

                _peerLastMediaAtMs.value = System.currentTimeMillis()
                val amp = (snapshot.child("amp").value as? Number)?.toFloat() ?: 0f
                val muted = snapshot.child("muted").getValue(Boolean::class.java) ?: false
                val seq = (snapshot.child("seq").value as? Number)?.toLong() ?: -1L
                val pcmStr = snapshot.child("pcm").getValue(String::class.java).orEmpty()

                _peerAudioMuted.value = muted
                _remoteVoiceAmplitude.value = if (muted) 0f else amp

                if (!muted && pcmStr.isNotEmpty() && seq != lastPlayedPeerSeq) {
                    lastPlayedPeerSeq = seq
                    scope.launch(Dispatchers.Default) {
                        if (!activeAudioCallId.get() || currentCallId != callId) return@launch
                        val decoded = decodeMuLawBase64(pcmStr)
                        if (decoded.isNotEmpty()) {
                            inboundPcmChannel.trySend(decoded)
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.d(TAG, "Peer audio listener cancelled: ${error.message}")
            }
        }
        peerAudioRef = ref
        peerAudioListener = listener
        ref.addValueEventListener(listener)
    }

    private fun removePeerAudioListener() {
        val listener = peerAudioListener
        val ref = peerAudioRef
        if (listener != null && ref != null) {
            runCatching { ref.removeEventListener(listener) }
        }
        peerAudioRef = null
        peerAudioListener = null
    }

    fun setMicrophoneMute(context: Context, muted: Boolean) {
        isLocalMicMuted = muted
        if (muted) _localVoiceAmplitude.value = 0f
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { am.isMicrophoneMute = muted }
    }

    fun setSpeakerphone(context: Context, speakerOn: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (speakerOn) {
                    val speakerDevice = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speakerDevice != null) {
                        am.setCommunicationDevice(speakerDevice)
                    } else {
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = true
                    }
                } else {
                    val earpieceDevice = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    }
                    if (earpieceDevice != null) {
                        am.setCommunicationDevice(earpieceDevice)
                    } else {
                        am.clearCommunicationDevice()
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = false
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = speakerOn
            }
        }
    }

    private fun cleanupAudioResourcesOnly() {
        activeAudioCallId.set(false)
        currentCallId = ""
        removePeerAudioListener()

        audioCaptureJob?.cancel()
        audioCaptureJob = null
        audioPlaybackJob?.cancel()
        audioPlaybackJob = null
        runCatching { inboundPcmChannel.close() }

        runCatching {
            echoCanceler?.release()
            noiseSuppressor?.release()
            audioRecord?.stop()
            audioRecord?.release()
        }
        echoCanceler = null
        noiseSuppressor = null
        audioRecord = null

        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null

        _localVoiceAmplitude.value = 0f
        _remoteVoiceAmplitude.value = 0f
        _peerAudioMuted.value = false
    }

    /**
     * Cleanly shuts down all audio hardware, RTDB listeners, and restores normal audio mode.
     */
    fun endCallAudio(context: Context) {
        val hadActive = activeAudioCallId.get()
        stopRinging()
        cleanupAudioResourcesOnly()

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am?.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            am?.isSpeakerphoneOn = false
            am?.isMicrophoneMute = false
            am?.mode = AudioManager.MODE_NORMAL

            if (hadActive) {
                val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 55)
                tg.startTone(ToneGenerator.TONE_PROP_PROMPT, 140)
                scope.launch {
                    delay(220L)
                    runCatching { tg.release() }
                }
            }
        }
    }

    // ==========================================
    // VIDEO STREAMING & CAMERAX ENGINE
    // ==========================================

    fun bindLocalCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        isFrontCamera: Boolean,
        isVideoMuted: Boolean,
        onFrameCaptured: (String) -> Unit
    ) {
        if (isVideoMuted || ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            unbindLocalCamera()
            return
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val provider = future.get()
                cameraProvider = provider
                provider.unbindAll()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                // 180p low-latency stream keeps CPU & RTDB bandwidth light on low-end phones
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(240, 180))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(frameAnalysisExecutor) { imageProxy ->
                    val now = System.currentTimeMillis()
                    if (now - lastFrameSendTime >= FRAME_INTERVAL_MS) {
                        lastFrameSendTime = now
                        val base64 = imageProxyToBase64Jpeg(imageProxy, isFrontCamera)
                        if (base64.isNotEmpty()) {
                            onFrameCaptured(base64)
                        }
                    }
                    imageProxy.close()
                }

                val selector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
            }.onFailure { err ->
                Log.e(TAG, "Error binding local camera: ${err.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbindLocalCamera() {
        runCatching {
            cameraProvider?.unbindAll()
        }
    }

    fun publishLocalVideoFrame(
        companyId: String,
        callId: String,
        myUid: String,
        frameBase64: String,
        isCameraOff: Boolean,
        context: Context?
    ) {
        val ref = callRef(companyId, callId, context)?.child("video")?.child(myUid) ?: return
        runCatching {
            ref.onDisconnect().removeValue()
            ref.setValue(
                mapOf(
                    "frame" to if (isCameraOff) "" else frameBase64,
                    "camOff" to isCameraOff,
                    "ts" to ServerValue.TIMESTAMP
                )
            )
        }
    }

    /**
     * Observes the remote peer's real-time video stream, decoding Bitmaps on a background pool
     * so the main UI thread never drops frames.
     */
    fun observeRemoteVideo(
        companyId: String,
        callId: String,
        peerUid: String,
        context: Context?
    ): Flow<Pair<Bitmap?, Boolean>> = callbackFlow {
        val ref = callRef(companyId, callId, context)?.child("video")?.child(peerUid)
        if (ref == null || peerUid.isBlank()) {
            trySend(null to true)
            close()
            return@callbackFlow
        }

        var decodeJob: Job? = null
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                _peerLastMediaAtMs.value = System.currentTimeMillis()
                val camOff = snapshot.child("camOff").getValue(Boolean::class.java) ?: false
                val frameStr = snapshot.child("frame").getValue(String::class.java).orEmpty()
                if (camOff || frameStr.isBlank()) {
                    decodeJob?.cancel()
                    trySend(null to true)
                } else {
                    decodeJob?.cancel()
                    decodeJob = scope.launch(Dispatchers.Default) {
                        val bmp = runCatching {
                            val bytes = Base64.decode(frameStr, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }.getOrNull()
                        trySend(bmp to false)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.d(TAG, "Remote video listener cancelled: ${error.message}")
            }
        }

        ref.addValueEventListener(listener)
        awaitClose {
            decodeJob?.cancel()
            ref.removeEventListener(listener)
        }
    }

    fun cleanupCallSession(companyId: String, callId: String, context: Context?) {
        val ref = callRef(companyId, callId, context) ?: return
        runCatching {
            ref.removeValue()
        }
    }

    // ==========================================
    // G.711 MU-LAW AUDIO CODEC (2:1 COMPRESSION)
    // ==========================================

    internal fun encodeMuLawBase64(pcm: ShortArray): String {
        val out = ByteArray(pcm.size)
        for (i in pcm.indices) {
            out[i] = linearToMuLaw(pcm[i])
        }
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    internal fun decodeMuLawBase64(encoded: String): ShortArray {
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return ShortArray(0)
        val pcm = ShortArray(bytes.size)
        for (i in bytes.indices) {
            pcm[i] = muLawToLinear(bytes[i])
        }
        return pcm
    }

    private fun linearToMuLaw(sample: Short): Byte {
        var pcmVal = sample.toInt()
        val mask: Int
        if (pcmVal < 0) {
            pcmVal = -pcmVal
            mask = 0x7F
        } else {
            mask = 0xFF
        }
        if (pcmVal > 32635) pcmVal = 32635
        pcmVal += 132

        var exponent = 7
        var expMask = 0x4000
        while ((pcmVal and expMask) == 0 && exponent > 0) {
            exponent--
            expMask = expMask shr 1
        }
        val mantissa = (pcmVal shr (exponent + 3)) and 0x0F
        val muLaw = (exponent shl 4) or mantissa
        return (muLaw xor mask).toByte()
    }

    private fun muLawToLinear(muLawByte: Byte): Short {
        val mu = (muLawByte.toInt() and 0xFF).inv()
        val sign = mu and 0x80
        val exponent = (mu shr 4) and 0x07
        val mantissa = mu and 0x0F
        var sample = ((mantissa shl 3) + 132) shl exponent
        sample -= 132
        return (if (sign != 0) -sample else sample).toShort()
    }

    // ==========================================
    // VIDEO FRAME CONVERSION
    // ==========================================

    private fun imageProxyToBase64Jpeg(image: ImageProxy, isFront: Boolean): String {
        return runCatching {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)

            val uvPixelStride = image.planes[1].pixelStride
            if (uvPixelStride == 1) {
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)
            } else {
                var offset = ySize
                val uRowStride = image.planes[1].rowStride
                val vRowStride = image.planes[2].rowStride
                val width = image.width
                val height = image.height
                for (row in 0 until height / 2) {
                    for (col in 0 until width / 2) {
                        val vIndex = row * vRowStride + col * uvPixelStride
                        val uIndex = row * uRowStride + col * uvPixelStride
                        nv21[offset++] = vBuffer.get(vIndex)
                        nv21[offset++] = uBuffer.get(uIndex)
                    }
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
            val jpegBytes = out.toByteArray()

            val rotationDegrees = image.imageInfo.rotationDegrees
            val finalBytes = if (rotationDegrees != 0 || isFront) {
                val originalBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                val matrix = Matrix().apply {
                    postRotate(rotationDegrees.toFloat())
                    if (isFront) postScale(-1f, 1f)
                }
                val rotatedBitmap = Bitmap.createBitmap(
                    originalBitmap,
                    0,
                    0,
                    originalBitmap.width,
                    originalBitmap.height,
                    matrix,
                    true
                )
                val rotatedOut = ByteArrayOutputStream()
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, rotatedOut)
                rotatedBitmap.recycle()
                originalBitmap.recycle()
                rotatedOut.toByteArray()
            } else {
                jpegBytes
            }

            Base64.encodeToString(finalBytes, Base64.NO_WRAP)
        }.getOrDefault("")
    }
}
