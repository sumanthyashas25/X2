package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AgencyRepository
import com.example.data.AuthSessionStatus
import com.example.data.AuthenticationRepository
import com.example.data.FirestoreInitializationService
import com.example.data.OperationType
import com.example.data.VerifiedUserRole
import com.example.data.handleFirestoreError
import com.example.model.User
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

sealed interface UserProfileState {
    data object Unauthenticated : UserProfileState
    data object Loading : UserProfileState
    data class Loaded(
        val user: User?,
        val email: String?,
        val verifiedRole: VerifiedUserRole = VerifiedUserRole()
    ) : UserProfileState
    data class Error(val message: String) : UserProfileState
}

/**
 * Retrieves and streams the authenticated user's [User] profile, access level,
 * and membership status from Firestore once signed in and Firestore is initialized.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserViewModel(application: Application) : AndroidViewModel(application) {

    private val authRepository = AuthenticationRepository(application)
    private val agencyRepository = AgencyRepository(application)
    private val firestoreInitService = FirestoreInitializationService.getInstance(application)

    private val _retryTrigger = MutableStateFlow(0)

    val verifiedUserRole: StateFlow<VerifiedUserRole> = authRepository.verifiedUserRoleFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = authRepository.currentVerifiedUserRole()
        )

    fun retryProfileSync() {
        _retryTrigger.value += 1
    }

    val userProfileState: StateFlow<UserProfileState> = combine(
        authRepository.sessionStatusFlow,
        _retryTrigger
    ) { session, _ -> session }
        .flatMapLatest { session ->
            when (session) {
                AuthSessionStatus.Initializing -> flowOf(UserProfileState.Loading)
                AuthSessionStatus.SignedOut -> {
                    firestoreInitService.onSignedOut()
                    flowOf(UserProfileState.Unauthenticated)
                }
                is AuthSessionStatus.SignedIn -> {
                    val uid = session.user.uid
                    if (uid.isBlank()) {
                        flowOf(UserProfileState.Unauthenticated)
                    } else {
                        flow {
                            emit(UserProfileState.Loading)
                            val initResult = firestoreInitService.initializeAfterAuth(session.user)
                            val initError = initResult.exceptionOrNull()
                            if (initError != null) {
                                if (initError is CancellationException) throw initError
                                emit(
                                    UserProfileState.Error(
                                        initError.localizedMessage ?: "Failed to initialize Firestore after sign-in."
                                    )
                                )
                                return@flow
                            }

                            emitAll(
                                combine(
                                    agencyRepository.observeMyMember(uid),
                                    agencyRepository.observeMyPrivateInfo(uid)
                                        .onStart { emit(null) }
                                        .catch { emit(null) }
                                ) { member, privateInfo ->
                                    val domainUser = member?.let {
                                        User.fromMember(
                                            member = it,
                                            privateInfo = privateInfo,
                                            email = session.user.email.orEmpty()
                                        )
                                    }
                                    UserProfileState.Loaded(
                                        user = domainUser,
                                        email = session.user.email,
                                        verifiedRole = AuthenticationRepository.verifyUserRole(session.user)
                                    ) as UserProfileState
                                }.catch { err ->
                                    if (err is CancellationException) throw err
                                    if (err is FirebaseFirestoreException) {
                                        handleFirestoreError(err, OperationType.GET, "companies/xmax/members/$uid")
                                    }
                                    emit(
                                        UserProfileState.Error(
                                            err.localizedMessage ?: "Failed to retrieve user profile from Firestore."
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserProfileState.Loading
        )
}
