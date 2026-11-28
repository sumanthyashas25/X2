/**
 * X.MAX Free-Tier Firebase + Cloudflare R2 Chat Storage & Push Proxy
 * - Runs 100% on Cloudflare Workers Free Plan + Cloudflare R2 Free Tier (10 GB/mo, $0 egress)
 * - Verifies Firebase Auth ID tokens via Google public JWKs (WebCrypto RS256 with cached JWKs)
 * - Verifies Firestore company membership & chat_channels access against named Firestore DB
 * - Streams uploads & authenticated downloads directly to/from Cloudflare R2 (CHAT_BUCKET)
 * - Dispatches optional FCM HTTP v1 push notifications & Super Admin R2+Firestore cleanup
 */

const GOOGLE_JWKS_URL =
  "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
const DEFAULT_FIREBASE_PROJECT_ID = "gen-lang-client-0411475132";
const DEFAULT_FIRESTORE_DB_ID =
  "ai-studio-android-x2-f008d1bb-6c44-4fa2-9fd3-87ddefd4d583";

function hasSupabaseConfig(env) {
  return Boolean(
    String(env.SUPABASE_URL || "").trim() &&
      String(env.SUPABASE_SERVICE_ROLE_KEY || "").trim()
  );
}

function getSupabaseBaseUrl(env) {
  return String(env.SUPABASE_URL || "").trim().replace(/\/+$/, "");
}

function resolveStorageProvider(env) {
  const forced = String(env.STORAGE_PROVIDER || "AUTO").trim().toUpperCase();
  if (forced === "SUPABASE_STORAGE" && hasSupabaseConfig(env)) return "SUPABASE_STORAGE";
  if (forced === "CLOUDFLARE_R2" && env.CHAT_BUCKET) return "CLOUDFLARE_R2";
  if (env.CHAT_BUCKET) return "CLOUDFLARE_R2";
  if (hasSupabaseConfig(env)) return "SUPABASE_STORAGE";
  return "UNCONFIGURED";
}

function isR2Active(env) {
  return resolveStorageProvider(env) === "CLOUDFLARE_R2" && Boolean(env.CHAT_BUCKET);
}

const ALLOWED_BUCKETS = {
  "avatars": {
    maxBytes: 2 * 1024 * 1024,
    mimes: ["image/jpeg", "image/png", "image/webp"],
  },
  "group-photos": {
    maxBytes: 2 * 1024 * 1024,
    mimes: ["image/jpeg", "image/png", "image/webp"],
  },
  "chat-media": {
    maxBytes: 5 * 1024 * 1024,
    mimes: ["image/jpeg", "image/png", "image/webp", "image/gif"],
  },
  "chat-voice": {
    maxBytes: 3 * 1024 * 1024,
    mimes: [
      "audio/mp4",
      "audio/aac",
      "audio/m4a",
      "audio/x-m4a",
      "audio/mpeg",
      "audio/wav",
      "audio/ogg",
      "audio/3gpp",
      "audio/amr",
    ],
  },
  "chat-videos": {
    maxBytes: 15 * 1024 * 1024,
    mimes: ["video/mp4", "video/webm", "video/quicktime", "video/3gpp", "image/jpeg"],
  },
  "chat-docs": {
    maxBytes: 10 * 1024 * 1024,
    mimes: [
      "application/pdf",
      "text/plain",
      "text/csv",
      "application/msword",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "application/vnd.ms-excel",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "application/vnd.ms-powerpoint",
      "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      "application/zip",
      "application/x-zip-compressed",
    ],
  },
};

const CHAT_RESET_COLLECTIONS = ["messages", "reactions", "receipts", "polls", "reports"];

let cachedJwks = null;
let jwksFetchedAt = 0;
const JWKS_TTL_MS = 3600_000;

const rateBuckets = new Map();
const MAX_RATE_BUCKETS = 10000;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const cors = buildCorsHeaders(request, env);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: cors });
    }

    if (request.method === "GET" && url.pathname === "/v1/health") {
      return jsonResponse(
        {
          ok: true,
          plan: "free",
          workerVersion: "2026.11.27",
          storageProvider: resolveStorageProvider(env),
          r2Configured: Boolean(env.CHAT_BUCKET),
          supabaseConfigured: hasSupabaseConfig(env),
          fcmConfigured: Boolean(env.SERVICE_ACCOUNT_JSON || env.FIREBASE_SERVICE_ACCOUNT_JSON),
          firebaseProjectId: env.FIREBASE_PROJECT_ID || DEFAULT_FIREBASE_PROJECT_ID,
          firestoreDbId: env.FIRESTORE_DB_ID || DEFAULT_FIRESTORE_DB_ID,
        },
        200,
        cors
      );
    }

    try {
      const projectId = String(env.FIREBASE_PROJECT_ID || DEFAULT_FIREBASE_PROJECT_ID).trim();
      const firestoreDbId = String(env.FIRESTORE_DB_ID || DEFAULT_FIRESTORE_DB_ID).trim();

      // Public read-only Client Portal guest link routes (token-authenticated, sanitized output only)
      if (url.pathname.startsWith("/portal/")) {
        return await handlePublicPortalRoute(request, url, projectId, firestoreDbId, env, cors);
      }

      const authHeader = request.headers.get("Authorization") || "";
      const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";
      if (!idToken) {
        return jsonResponse({ error: "Missing Firebase Auth Bearer token" }, 401, cors);
      }

      const claims = await verifyFirebaseIdToken(idToken, projectId);
      const uid = claims.sub;

      const clientIp = request.headers.get("CF-Connecting-IP") || "global";
      if (!checkRateLimit(`${clientIp}:${uid}:${url.pathname}`, 60, 60_000)) {
        return jsonResponse(
          { error: "Rate limit exceeded" },
          429,
          { ...cors, "Retry-After": "30" }
        );
      }

      if ((request.method === "GET" || request.method === "POST") && url.pathname === "/v1/auth/verify") {
        const companyId = sanitizeId(url.searchParams.get("companyId") || "xmax");
        let member = null;
        if (companyId) {
          member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId).catch(() => null);
        }
        return jsonResponse(
          {
            ok: true,
            uid,
            email: claims.email || "",
            emailVerified: Boolean(claims.email_verified),
            firebaseProjectId: projectId,
            firestoreDbId,
            storageProvider: resolveStorageProvider(env),
            r2Configured: Boolean(env.CHAT_BUCKET),
            supabaseConfigured: hasSupabaseConfig(env),
            fcmConfigured: Boolean(env.SERVICE_ACCOUNT_JSON || env.FIREBASE_SERVICE_ACCOUNT_JSON),
            member: member
              ? {
                  status: member.status || "",
                  accessLevel: member.accessLevel || "",
                  active: member.active !== false,
                }
              : null,
          },
          200,
          cors
        );
      }

      // Direct Upload (Cloudflare R2 primary, Supabase Storage fallback)
      if (request.method === "POST" && url.pathname === "/v1/storage/upload") {
        return await handleR2Upload(request, url, uid, idToken, projectId, firestoreDbId, env, cors);
      }

      // Authenticated Download (Cloudflare R2 primary, Supabase Storage fallback)
      if (request.method === "GET" && url.pathname === "/v1/storage/download") {
        return await handleR2Download(url, uid, idToken, projectId, firestoreDbId, env, cors);
      }

      // Cloud storage quota & file usage listing (Cloudflare R2 or Supabase Storage)
      if (request.method === "GET" && url.pathname === "/v1/storage/usage") {
        return await handleStorageUsage(url, uid, idToken, projectId, firestoreDbId, env, cors);
      }

      // Authenticated Delete (Cloudflare R2 primary, Supabase Storage fallback)
      if (request.method === "DELETE" && url.pathname === "/v1/storage/object") {
        const body = await request.json().catch(() => ({}));
        return await handleR2Delete(uid, idToken, body, projectId, firestoreDbId, env, cors);
      }

      if (request.method !== "POST") {
        return jsonResponse({ error: "Method not allowed" }, 405, cors);
      }

      const body = await request.json().catch(() => ({}));
      const companyId = sanitizeId(body.companyId || "");
      if (!companyId) {
        return jsonResponse({ error: "Missing companyId" }, 400, cors);
      }

      if (url.pathname === "/v1/storage/sign-upload") {
        return await handleSupabaseSignUpload(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors);
      }
      if (url.pathname === "/v1/storage/sign-download") {
        return await handleSupabaseSignDownload(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors);
      }
      if (url.pathname === "/v1/storage/delete-batch") {
        return await handleStorageDeleteBatch(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors);
      }
      if (url.pathname === "/v1/notifications/dispatch") {
        return await handlePushDispatch(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors);
      }
      if (url.pathname === "/v1/admin/reset-chunk") {
        return await handleResetChunk(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors);
      }
      if (url.pathname === "/v1/nudges/dispatch") {
        return await handlePaymentNudgeDispatch(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors);
      }

      return jsonResponse({ error: "Not found" }, 404, cors);
    } catch (err) {
      return jsonResponse(
        { error: err.message || "Unauthorized or bad request" },
        err.status || 400,
        cors
      );
    }
  },
};

function buildCorsHeaders(request, env) {
  const origin = request.headers.get("Origin") || "";
  const allowedRaw = String(env.ALLOWED_ORIGINS || "").trim();
  if (!origin || !allowedRaw) {
    return { "Vary": "Origin" };
  }
  const allowedList = allowedRaw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  if (!allowedList.includes(origin)) {
    return { "Vary": "Origin" };
  }
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Authorization, Content-Type",
    "Vary": "Origin",
  };
}

function jsonResponse(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      "X-Content-Type-Options": "nosniff",
      ...extraHeaders,
    },
  });
}

function sanitizeId(id) {
  return String(id || "").replace(/[^a-zA-Z0-9_\-\.]/g, "");
}

function sanitizeFileName(name) {
  const base = String(name || "file")
    .split(/[\\/]/)
    .pop()
    .replace(/[^a-zA-Z0-9._\-]/g, "_")
    .replace(/^\.+/, "")
    .slice(0, 120);
  return base || "file";
}

function isSafeStoragePath(path) {
  if (!path || typeof path !== "string") return false;
  if (path.startsWith("/") || path.includes("..") || path.includes("//") || path.includes("\\")) {
    return false;
  }
  return /^[a-zA-Z0-9_\-\.\/]+$/.test(path) && path.length <= 500;
}

function checkRateLimit(key, limit, windowMs) {
  const now = Date.now();
  if (rateBuckets.size >= MAX_RATE_BUCKETS) {
    const oldestKey = rateBuckets.keys().next().value;
    rateBuckets.delete(oldestKey);
  }
  let bucket = rateBuckets.get(key);
  if (!bucket || now > bucket.resetTime) {
    bucket = { count: 1, resetTime: now + windowMs };
    rateBuckets.set(key, bucket);
    return true;
  }
  bucket.count++;
  return bucket.count <= limit;
}

function decodeBase64UrlToUint8Array(b64url) {
  let b64 = String(b64url || "").replace(/-/g, "+").replace(/_/g, "/");
  while (b64.length % 4 !== 0) {
    b64 += "=";
  }
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function decodeBase64UrlJson(b64url) {
  const bytes = decodeBase64UrlToUint8Array(b64url);
  return JSON.parse(new TextDecoder().decode(bytes));
}

async function verifyFirebaseIdToken(token, projectId) {
  const parts = token.split(".");
  if (parts.length !== 3) {
    const err = new Error("Invalid JWT structure");
    err.status = 401;
    throw err;
  }

  let header, payload;
  try {
    header = decodeBase64UrlJson(parts[0]);
    payload = decodeBase64UrlJson(parts[1]);
  } catch (e) {
    const err = new Error("Invalid JWT encoding");
    err.status = 401;
    throw err;
  }

  if (header.alg !== "RS256" || !header.kid) {
    const err = new Error("Unsupported JWT algorithm or missing kid");
    err.status = 401;
    throw err;
  }

  const now = Math.floor(Date.now() / 1000);
  if (!payload.exp || payload.exp < now) {
    const err = new Error("Firebase token expired");
    err.status = 401;
    throw err;
  }
  if (!payload.iat || payload.iat > now + 300) {
    const err = new Error("Firebase token issued in future");
    err.status = 401;
    throw err;
  }
  if (payload.iss !== `https://securetoken.google.com/${projectId}`) {
    const err = new Error("Invalid Firebase token issuer");
    err.status = 401;
    throw err;
  }
  if (payload.aud !== projectId) {
    const err = new Error("Invalid Firebase token audience");
    err.status = 401;
    throw err;
  }
  if (!payload.sub || typeof payload.sub !== "string" || payload.sub.length === 0) {
    const err = new Error("Invalid Firebase token subject");
    err.status = 401;
    throw err;
  }

  let jwks = await fetchGoogleJwks(false);
  let jwk = jwks.find((j) => j.kid === header.kid);
  if (!jwk) {
    jwks = await fetchGoogleJwks(true);
    jwk = jwks.find((j) => j.kid === header.kid);
  }
  if (!jwk) {
    const err = new Error("Matching Firebase signing key not found");
    err.status = 401;
    throw err;
  }

  const pubKey = await crypto.subtle.importKey(
    "jwk",
    jwk,
    { name: "RSASSA-PKCS1-v1_5", hash: { name: "SHA-256" } },
    false,
    ["verify"]
  );

  const data = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
  const sig = decodeBase64UrlToUint8Array(parts[2]);

  const valid = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", pubKey, sig, data);
  if (!valid) {
    const err = new Error("Invalid Firebase token signature");
    err.status = 401;
    throw err;
  }

  return payload;
}

async function fetchGoogleJwks(forceRefresh) {
  const now = Date.now();
  if (!forceRefresh && cachedJwks && now - jwksFetchedAt < JWKS_TTL_MS) {
    return cachedJwks;
  }
  const res = await fetch(GOOGLE_JWKS_URL);
  if (!res.ok) throw new Error("Failed to fetch Google public JWKs");
  const data = await res.json();
  cachedJwks = data.keys || [];
  jwksFetchedAt = now;
  return cachedJwks;
}

async function fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId) {
  const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}/members/${uid}`;
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${idToken}` },
  });
  if (!res.ok) {
    const errObj = await res.json().catch(() => ({}));
    const err = new Error(errObj.error?.message || "Membership verification failed");
    err.status = res.status === 404 ? 403 : res.status;
    throw err;
  }
  return parseFirestoreDoc(await res.json());
}

function assertApprovedNotSuspended(member) {
  if (
    !member ||
    member.status !== "APPROVED" ||
    member.isSuspended === true ||
    member.active !== true ||
    member.messagingSuspended === true
  ) {
    const err = new Error("User account is inactive, suspended, or unauthorized");
    err.status = 403;
    throw err;
  }
}

async function fetchChannelOrChatDoc(companyId, chatId, idToken, projectId, firestoreDbId) {
  // Primary chat collection used by ChatRepository.kt
  const channelUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}/chat_channels/${chatId}`;
  const chRes = await fetch(channelUrl, {
    headers: { Authorization: `Bearer ${idToken}` },
  });
  if (chRes.ok) {
    return { source: "chat_channels", doc: parseFirestoreDoc(await chRes.json()) };
  }

  // Fallback legacy chats collection
  const legacyUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}/chats/${chatId}`;
  const legRes = await fetch(legacyUrl, {
    headers: { Authorization: `Bearer ${idToken}` },
  });
  if (legRes.ok) {
    return { source: "chats", doc: parseFirestoreDoc(await legRes.json()) };
  }

  const err = new Error("Chat channel not found or access denied");
  err.status = 403;
  throw err;
}

async function assertChatParticipant(companyId, chatId, uid, member, idToken, projectId, firestoreDbId) {
  const { source, doc } = await fetchChannelOrChatDoc(
    companyId,
    chatId,
    idToken,
    projectId,
    firestoreDbId
  );

  const isSuperOrAdmin =
    member.accessLevel === "SUPER_ADMIN" ||
    member.accessLevel === "ADMIN" ||
    member.role === "SUPER_ADMIN" ||
    member.role === "ADMIN";

  if (source === "chat_channels") {
    const channelType = String(doc.channelType || "GENERAL").toUpperCase();
    const participants = Array.isArray(doc.participantUids) ? doc.participantUids : [];
    if (channelType === "DIRECT") {
      if (!participants.includes(uid)) {
        const err = new Error("User is not a participant in this direct chat");
        err.status = 403;
        throw err;
      }
      return { chat: doc, isSuperOrAdmin, isParticipant: true };
    }
    if (channelType === "SAVED") {
      if (doc.createdByUid !== uid && !participants.includes(uid)) {
        const err = new Error("Unauthorized saved messages access");
        err.status = 403;
        throw err;
      }
      return { chat: doc, isSuperOrAdmin, isParticipant: true };
    }
    if (channelType === "GROUP") {
      if (!participants.includes(uid) && !isSuperOrAdmin) {
        const err = new Error("User is not a member of this group chat");
        err.status = 403;
        throw err;
      }
      return { chat: doc, isSuperOrAdmin, isParticipant: participants.includes(uid) || isSuperOrAdmin };
    }
    if (["GENERAL", "PROJECT", "DEPARTMENT", "ANNOUNCEMENT"].includes(channelType)) {
      return { chat: doc, isSuperOrAdmin, isParticipant: true };
    }
    const err = new Error("Unsupported chat channel type");
    err.status = 403;
    throw err;
  }

  // Legacy chats validation
  const legacyType = String(doc.type || "").toUpperCase();
  const participants = Array.isArray(doc.participantIds)
    ? doc.participantIds
    : Array.isArray(doc.participants)
      ? doc.participants
      : [];

  if (legacyType === "SAVED" || legacyType === "SAVED_MESSAGES") {
    if (doc.ownerUid !== uid) {
      const err = new Error("Unauthorized saved messages access");
      err.status = 403;
      throw err;
    }
    return { chat: doc, isSuperOrAdmin, isParticipant: true };
  }

  if (!participants.includes(uid) && !["ANNOUNCEMENT", "CHANNEL"].includes(legacyType) && !isSuperOrAdmin) {
    const err = new Error("User is not a participant of this chat");
    err.status = 403;
    throw err;
  }

  return { chat: doc, isSuperOrAdmin, isParticipant: participants.includes(uid) };
}

function parseFirestoreDoc(doc) {
  const fields = doc.fields || {};
  const result = {};
  for (const [key, val] of Object.entries(fields)) {
    if (val.stringValue !== undefined) result[key] = val.stringValue;
    else if (val.booleanValue !== undefined) result[key] = val.booleanValue;
    else if (val.integerValue !== undefined) result[key] = Number(val.integerValue);
    else if (val.doubleValue !== undefined) result[key] = Number(val.doubleValue);
    else if (val.arrayValue !== undefined) {
      result[key] = (val.arrayValue.values || []).map(
        (v) => v.stringValue ?? v.integerValue ?? v.booleanValue ?? ""
      );
    } else if (val.mapValue !== undefined) {
      result[key] = parseFirestoreDoc({ fields: val.mapValue.fields });
    }
  }
  return result;
}

async function handleR2Upload(request, url, uid, idToken, projectId, firestoreDbId, env, cors) {
  if (!env.CHAT_BUCKET && !hasSupabaseConfig(env)) {
    return jsonResponse(
      { error: "Neither Cloudflare R2 (CHAT_BUCKET) nor Supabase Storage is configured" },
      500,
      cors
    );
  }

  const companyId = sanitizeId(url.searchParams.get("companyId") || "");
  const chatId = sanitizeId(url.searchParams.get("chatId") || "");
  const bucket = String(url.searchParams.get("bucket") || "chat-docs");
  const rawName = url.searchParams.get("fileName") || "attachment";
  const sha256 = sanitizeId(url.searchParams.get("sha256") || "").slice(0, 128);
  const rawContentType = (request.headers.get("Content-Type") || "").split(";")[0].trim().toLowerCase();

  if (!companyId) return jsonResponse({ error: "Missing companyId" }, 400, cors);

  const policy = ALLOWED_BUCKETS[bucket];
  if (!policy) return jsonResponse({ error: "Unsupported storage bucket" }, 400, cors);

  if (!policy.mimes.includes(rawContentType)) {
    return jsonResponse({ error: `Unsupported MIME type: ${rawContentType || "unknown"}` }, 415, cors);
  }

  const contentLenHeader = Number(request.headers.get("Content-Length") || 0);
  if (contentLenHeader > policy.maxBytes) {
    return jsonResponse(
      { error: `File size exceeds free-tier limit (${policy.maxBytes} bytes)` },
      413,
      cors
    );
  }

  const member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId);
  assertApprovedNotSuspended(member);

  const scope = sanitizeId(url.searchParams.get("scope") || "");
  let prefix = "";
  if (bucket === "avatars") {
    prefix = `companies/${companyId}/avatars/${uid}/`;
  } else if (scope === "status" || chatId === "status_feed") {
    if (!["chat-media", "chat-videos", "chat-voice"].includes(bucket)) {
      return jsonResponse({ error: "Unsupported status media bucket" }, 400, cors);
    }
    prefix = `companies/${companyId}/statuses/${uid}/`;
  } else if (bucket === "group-photos") {
    if (!chatId) return jsonResponse({ error: "Missing chatId for group photo" }, 400, cors);
    await assertChatParticipant(companyId, chatId, uid, member, idToken, projectId, firestoreDbId);
    prefix = `companies/${companyId}/group-photos/${chatId}/`;
  } else {
    if (!chatId) return jsonResponse({ error: "Missing chatId for chat attachment" }, 400, cors);
    await assertChatParticipant(companyId, chatId, uid, member, idToken, projectId, firestoreDbId);
    prefix = `companies/${companyId}/chats/${chatId}/`;
  }

  const safeName = sanitizeFileName(rawName);
  const storagePath = `${prefix}${crypto.randomUUID()}_${safeName}`;
  if (!isSafeStoragePath(storagePath)) {
    return jsonResponse({ error: "Invalid storage path" }, 400, cors);
  }

  const bytes = await request.arrayBuffer();
  if (bytes.byteLength <= 0) {
    return jsonResponse({ error: "Empty file payload" }, 400, cors);
  }
  if (bytes.byteLength > policy.maxBytes) {
    return jsonResponse(
      { error: `File size exceeds free-tier limit (${policy.maxBytes} bytes)` },
      413,
      cors
    );
  }

  if (isR2Active(env)) {
    await env.CHAT_BUCKET.put(storagePath, bytes, {
      httpMetadata: {
        contentType: rawContentType,
      },
      customMetadata: {
        companyId,
        chatId,
        bucket,
        uploadedBy: uid,
        sha256,
        originalName: safeName,
      },
    });
    return jsonResponse(
      {
        ok: true,
        provider: "CLOUDFLARE_R2",
        bucket,
        storagePath,
        byteSize: bytes.byteLength,
        mimeType: rawContentType,
        sha256,
      },
      200,
      cors
    );
  }

  const supabaseBase = getSupabaseBaseUrl(env);
  const upRes = await fetch(
    `${supabaseBase}/storage/v1/object/${encodeURIComponent(bucket)}/${encodeURI(storagePath)}`,
    {
      method: "POST",
      headers: {
        apikey: env.SUPABASE_SERVICE_ROLE_KEY,
        Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
        "Content-Type": rawContentType,
        "x-upsert": "true",
      },
      body: bytes,
    }
  );
  if (!upRes.ok) {
    const errText = await upRes.text().catch(() => "");
    return jsonResponse({ error: `Supabase upload failed: ${errText || upRes.status}` }, 502, cors);
  }

  return jsonResponse(
    {
      ok: true,
      provider: "SUPABASE_STORAGE",
      bucket,
      storagePath,
      byteSize: bytes.byteLength,
      mimeType: rawContentType,
      sha256,
    },
    200,
    cors
  );
}

async function handleR2Download(url, uid, idToken, projectId, firestoreDbId, env, cors) {
  if (!env.CHAT_BUCKET && !hasSupabaseConfig(env)) {
    return jsonResponse(
      { error: "Neither Cloudflare R2 (CHAT_BUCKET) nor Supabase Storage is configured" },
      500,
      cors
    );
  }

  const companyId = sanitizeId(url.searchParams.get("companyId") || "");
  const chatId = sanitizeId(url.searchParams.get("chatId") || "");
  const statusId = sanitizeId(url.searchParams.get("statusId") || "");
  const storagePath = String(url.searchParams.get("storagePath") || "").replace(/^\/+/, "");

  if (!companyId) return jsonResponse({ error: "Missing companyId" }, 400, cors);
  if (!isSafeStoragePath(storagePath)) {
    return jsonResponse({ error: "Invalid storagePath" }, 400, cors);
  }

  const member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId);
  assertApprovedNotSuspended(member);

  const isStatusMedia = storagePath.startsWith(`companies/${companyId}/statuses/`);
  const isAvatar = storagePath.startsWith(`companies/${companyId}/avatars/`);
  const isGroupPhoto = storagePath.startsWith(`companies/${companyId}/group-photos/`);

  if (isStatusMedia) {
    if (!statusId) {
      return jsonResponse({ error: "Missing statusId for status media" }, 400, cors);
    }
    const stUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}/chat_statuses/${statusId}`;
    const stRes = await fetch(stUrl, { headers: { Authorization: `Bearer ${idToken}` } });
    if (!stRes.ok) {
      return jsonResponse({ error: "Status access denied or unavailable" }, stRes.status === 404 ? 404 : 403, cors);
    }
    const stDoc = parseFirestoreDoc(await stRes.json());
    if (stDoc.expiresAtMillis && Number(stDoc.expiresAtMillis) < Date.now()) {
      return jsonResponse({ error: "Status has expired" }, 410, cors);
    }
    if (stDoc.ownerUid && stDoc.ownerUid !== uid) {
      const blocked = Array.isArray(stDoc.ownerBlockedUids) ? stDoc.ownerBlockedUids : [];
      if (blocked.includes(uid)) {
        return jsonResponse({ error: "Blocked user cannot view status media" }, 403, cors);
      }
      const mode = stDoc.privacyMode || "ALL_APPROVED";
      const allowed = Array.isArray(stDoc.allowedUids) ? stDoc.allowedUids : [];
      const excluded = Array.isArray(stDoc.excludedUids) ? stDoc.excludedUids : [];
      if (mode === "SELECTED_ONLY" && !allowed.includes(uid)) {
        return jsonResponse({ error: "You do not have permission to view this status" }, 403, cors);
      }
      if (mode === "ALL_EXCEPT" && excluded.includes(uid)) {
        return jsonResponse({ error: "You do not have permission to view this status" }, 403, cors);
      }
    }
  } else if (isGroupPhoto) {
    const inferredGroupChatId = storagePath
      .slice(`companies/${companyId}/group-photos/`.length)
      .split("/")[0] || "";
    const resolvedChatId = chatId || sanitizeId(inferredGroupChatId);
    if (!resolvedChatId) {
      return jsonResponse({ error: "Missing chatId for group photo" }, 400, cors);
    }
    const expectedGroupPrefix = `companies/${companyId}/group-photos/${resolvedChatId}/`;
    if (!storagePath.startsWith(expectedGroupPrefix)) {
      return jsonResponse({ error: "Path mismatch for group photo" }, 403, cors);
    }
    await assertChatParticipant(companyId, resolvedChatId, uid, member, idToken, projectId, firestoreDbId);
  } else if (!isAvatar) {
    if (!chatId) return jsonResponse({ error: "Missing chatId" }, 400, cors);
    const expectedPrefix = `companies/${companyId}/chats/${chatId}/`;
    if (!storagePath.startsWith(expectedPrefix)) {
      return jsonResponse({ error: "Path mismatch for chat attachment" }, 403, cors);
    }
    await assertChatParticipant(companyId, chatId, uid, member, idToken, projectId, firestoreDbId);
  }

  if (isR2Active(env)) {
    const object = await env.CHAT_BUCKET.get(storagePath);
    if (!object) {
      return jsonResponse({ error: "File not found in Cloudflare R2" }, 404, cors);
    }

    const headers = new Headers(cors);
    object.writeHttpMetadata(headers);
    const downloadName = sanitizeFileName(
      object.customMetadata?.originalName || storagePath.split("/").pop() || "download"
    );
    headers.set("Content-Disposition", `attachment; filename="${downloadName}"`);
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("Cache-Control", "private, max-age=3600");
    headers.set("ETag", object.httpEtag);
    if (typeof object.size === "number") {
      headers.set("Content-Length", String(object.size));
    }

    return new Response(object.body, {
      status: 200,
      headers,
    });
  }

  const bucketHint = sanitizeId(url.searchParams.get("bucket") || inferSupabaseBucketFromPath(storagePath));
  const supabaseBase = getSupabaseBaseUrl(env);
  const dlRes = await fetch(
    `${supabaseBase}/storage/v1/object/${encodeURIComponent(bucketHint)}/${encodeURI(storagePath)}`,
    {
      headers: {
        apikey: env.SUPABASE_SERVICE_ROLE_KEY,
        Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
      },
    }
  );
  if (!dlRes.ok) {
    return jsonResponse(
      { error: dlRes.status === 404 ? "File not found in Supabase Storage" : "Supabase download failed" },
      dlRes.status === 404 ? 404 : 502,
      cors
    );
  }
  const headers = new Headers(cors);
  const contentType = dlRes.headers.get("Content-Type") || "application/octet-stream";
  const downloadName = sanitizeFileName(storagePath.split("/").pop() || "download");
  headers.set("Content-Type", contentType);
  headers.set("Content-Disposition", `attachment; filename="${downloadName}"`);
  headers.set("X-Content-Type-Options", "nosniff");
  headers.set("Cache-Control", "private, max-age=3600");
  return new Response(dlRes.body, { status: 200, headers });
}

function inferSupabaseBucketFromPath(storagePath) {
  if (storagePath.includes("/avatars/")) return "avatars";
  if (storagePath.includes("/group-photos/")) return "group-photos";
  if (/\.(mp4|webm|mov|3gp)$/i.test(storagePath)) return "chat-videos";
  if (/\.(mp3|m4a|aac|wav|ogg|amr)$/i.test(storagePath)) return "chat-voice";
  if (/\.(jpg|jpeg|png|webp|gif)$/i.test(storagePath)) return "chat-media";
  return "chat-docs";
}

async function handleR2Delete(uid, idToken, body, projectId, firestoreDbId, env, cors) {
  if (!env.CHAT_BUCKET && !hasSupabaseConfig(env)) {
    return jsonResponse(
      { error: "Neither Cloudflare R2 (CHAT_BUCKET) nor Supabase Storage is configured" },
      500,
      cors
    );
  }

  const companyId = sanitizeId(body.companyId || "");
  const chatId = sanitizeId(body.chatId || "");
  const storagePath = String(body.storagePath || "").replace(/^\/+/, "");

  if (!companyId || !isSafeStoragePath(storagePath)) {
    return jsonResponse({ error: "Invalid companyId or storagePath" }, 400, cors);
  }
  if (!storagePath.startsWith(`companies/${companyId}/`)) {
    return jsonResponse({ error: "Unauthorized company storage path" }, 403, cors);
  }

  const member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId);
  assertApprovedNotSuspended(member);
  const isSuperAdmin = member.accessLevel === "SUPER_ADMIN" || member.role === "SUPER_ADMIN";

  if (chatId && storagePath.includes("/chats/") && !storagePath.startsWith(`companies/${companyId}/chats/${chatId}/`)) {
    return jsonResponse({ error: "Chat ID mismatch" }, 403, cors);
  }

  if (isR2Active(env)) {
    const head = await env.CHAT_BUCKET.head(storagePath);
    if (!head) {
      return jsonResponse({ deleted: true, alreadyMissing: true }, 200, cors);
    }

    const isUploader = head.customMetadata?.uploadedBy === uid;
    if (!isSuperAdmin && !isUploader) {
      return jsonResponse({ error: "Only the uploader or Super Admin can delete this file" }, 403, cors);
    }

    await env.CHAT_BUCKET.delete(storagePath);
    return jsonResponse({ deleted: true, storagePath }, 200, cors);
  }

  if (!isSuperAdmin && !storagePath.includes(`/${uid}/`)) {
    await assertChatParticipant(companyId, chatId || "general", uid, member, idToken, projectId, firestoreDbId);
  }
  const bucket = sanitizeId(body.bucket || inferSupabaseBucketFromPath(storagePath));
  const supabaseBase = getSupabaseBaseUrl(env);
  const delRes = await fetch(
    `${supabaseBase}/storage/v1/object/${encodeURIComponent(bucket)}/${encodeURI(storagePath)}`,
    {
      method: "DELETE",
      headers: {
        apikey: env.SUPABASE_SERVICE_ROLE_KEY,
        Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
      },
    }
  );
  if (!delRes.ok && delRes.status !== 404) {
    return jsonResponse({ error: "Supabase delete failed" }, 502, cors);
  }
  return jsonResponse({ deleted: true, storagePath }, 200, cors);
}

function classifyStoragePath(companyId, storagePath) {
  const rest = storagePath.replace(`companies/${companyId}/`, "");
  const parts = rest.split("/");
  const folder = parts[0] || "other";
  const subId = parts[1] || "";
  const fileName = parts[parts.length - 1] || "file";
  const category =
    folder === "chats" ? "chats" :
    folder === "statuses" ? "statuses" :
    folder === "avatars" ? "avatars" :
    folder === "group-photos" ? "groups" : "other";
  return {
    category,
    chatId: folder === "chats" || folder === "group-photos" ? subId : "",
    uploaderUid: folder === "statuses" || folder === "avatars" ? subId : "",
    fileName,
  };
}

async function handleStorageUsage(url, uid, idToken, projectId, firestoreDbId, env, cors) {
  const companyId = sanitizeId(url.searchParams.get("companyId") || "xmax");
  if (!companyId) return jsonResponse({ error: "Missing companyId" }, 400, cors);

  const member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId);
  assertApprovedNotSuspended(member);
  const isSuperOrAdmin =
    member.accessLevel === "SUPER_ADMIN" ||
    member.accessLevel === "ADMIN" ||
    member.role === "SUPER_ADMIN" ||
    member.role === "ADMIN";

  const provider = resolveStorageProvider(env);
  if (provider === "UNCONFIGURED") {
    return jsonResponse(
      {
        ok: true,
        provider: "UNCONFIGURED",
        quotaBytes: 1024 * 1024 * 1024,
        totalBytes: 0,
        objectCount: 0,
        truncated: false,
        byCategory: {},
        objects: [],
      },
      200,
      cors
    );
  }

  const byCategory = {
    chats: { bytes: 0, count: 0 },
    statuses: { bytes: 0, count: 0 },
    avatars: { bytes: 0, count: 0 },
    groups: { bytes: 0, count: 0 },
    other: { bytes: 0, count: 0 },
  };
  const objects = [];
  let totalBytes = 0;
  let objectCount = 0;
  let truncated = false;

  if (isR2Active(env)) {
    const quotaBytes = 10 * 1024 * 1024 * 1024; // 10 GB Cloudflare R2 Free Tier
    let cursor = undefined;
    for (let page = 0; page < 5; page++) {
      const listed = await env.CHAT_BUCKET.list({
        prefix: `companies/${companyId}/`,
        limit: 500,
        cursor,
        include: ["customMetadata"],
      });
      for (const obj of listed.objects || []) {
        const size = Number(obj.size || 0);
        const meta = classifyStoragePath(companyId, obj.key);
        const uploaderUid = obj.customMetadata?.uploadedBy || meta.uploaderUid || "";
        const bucket = obj.customMetadata?.bucket || inferSupabaseBucketFromPath(obj.key);
        totalBytes += size;
        objectCount += 1;
        byCategory[meta.category].bytes += size;
        byCategory[meta.category].count += 1;
        if (objects.length < 250) {
          objects.push({
            bucket,
            storagePath: obj.key,
            fileName: obj.customMetadata?.originalName || meta.fileName,
            byteSize: size,
            category: meta.category,
            chatId: obj.customMetadata?.chatId || meta.chatId,
            uploaderUid,
            updatedAtMillis: obj.uploaded ? new Date(obj.uploaded).getTime() : 0,
            canDelete: isSuperOrAdmin || uploaderUid === uid,
          });
        }
      }
      if (!listed.truncated || !listed.cursor) break;
      cursor = listed.cursor;
      if (page === 4) truncated = true;
    }
    objects.sort((a, b) => b.byteSize - a.byteSize);
    return jsonResponse(
      {
        ok: true,
        provider: "CLOUDFLARE_R2",
        quotaBytes,
        totalBytes,
        objectCount,
        truncated,
        byCategory,
        objects,
      },
      200,
      cors
    );
  }

  // Supabase Storage Free Tier (1 GB)
  const quotaBytes = 1024 * 1024 * 1024;
  const supabaseBase = getSupabaseBaseUrl(env);
  const bucketNames = Object.keys(ALLOWED_BUCKETS);
  let subrequestBudget = 36; // stay comfortably inside Cloudflare Free 50-subrequest limit

  for (const bucket of bucketNames) {
    if (subrequestBudget <= 0) {
      truncated = true;
      break;
    }
    const queue = [`companies/${companyId}`];
    while (queue.length > 0 && subrequestBudget > 0) {
      const prefix = queue.shift();
      subrequestBudget -= 1;
      const listRes = await fetch(`${supabaseBase}/storage/v1/object/list/${encodeURIComponent(bucket)}`, {
        method: "POST",
        headers: {
          apikey: env.SUPABASE_SERVICE_ROLE_KEY,
          Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          prefix,
          limit: 200,
          offset: 0,
          sortBy: { column: "updated_at", order: "desc" },
        }),
      });
      if (!listRes.ok) continue;
      const entries = await listRes.json().catch(() => []);
      if (!Array.isArray(entries)) continue;
      for (const entry of entries) {
        if (!entry || !entry.name) continue;
        const fullPath = `${prefix}/${entry.name}`;
        if (!entry.id) {
          // Folder node
          if (queue.length < 25) queue.push(fullPath);
          continue;
        }
        const size = Number(entry.metadata?.size || 0);
        const meta = classifyStoragePath(companyId, fullPath);
        totalBytes += size;
        objectCount += 1;
        byCategory[meta.category].bytes += size;
        byCategory[meta.category].count += 1;
        if (objects.length < 250) {
          objects.push({
            bucket,
            storagePath: fullPath,
            fileName: meta.fileName,
            byteSize: size,
            category: meta.category,
            chatId: meta.chatId,
            uploaderUid: meta.uploaderUid,
            updatedAtMillis: entry.updated_at ? new Date(entry.updated_at).getTime() : 0,
            canDelete: isSuperOrAdmin || meta.uploaderUid === uid || meta.category === "chats",
          });
        }
      }
      if (queue.length > 0 && subrequestBudget <= 0) {
        truncated = true;
      }
    }
  }

  objects.sort((a, b) => b.byteSize - a.byteSize);
  return jsonResponse(
    {
      ok: true,
      provider: "SUPABASE_STORAGE",
      quotaBytes,
      totalBytes,
      objectCount,
      truncated,
      byCategory,
      objects,
    },
    200,
    cors
  );
}

async function handleStorageDeleteBatch(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors) {
  if (!env.CHAT_BUCKET && !hasSupabaseConfig(env)) {
    return jsonResponse({ error: "Storage backend not configured" }, 500, cors);
  }
  const items = Array.isArray(body.items) ? body.items.slice(0, 60) : [];
  if (items.length === 0) return jsonResponse({ deletedCount: 0 }, 200, cors);

  const member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId);
  assertApprovedNotSuspended(member);
  const isSuperOrAdmin =
    member.accessLevel === "SUPER_ADMIN" ||
    member.accessLevel === "ADMIN" ||
    member.role === "SUPER_ADMIN" ||
    member.role === "ADMIN";

  const validItems = [];
  for (const item of items) {
    const storagePath = String(item?.storagePath || "").replace(/^\/+/, "");
    if (!isSafeStoragePath(storagePath) || !storagePath.startsWith(`companies/${companyId}/`)) {
      continue;
    }
    const meta = classifyStoragePath(companyId, storagePath);
    if (!isSuperOrAdmin && meta.uploaderUid !== uid) {
      if (meta.category === "chats" && meta.chatId) {
        try {
          await assertChatParticipant(companyId, meta.chatId, uid, member, idToken, projectId, firestoreDbId);
        } catch {
          continue;
        }
      } else {
        continue;
      }
    }
    validItems.push({
      storagePath,
      bucket: sanitizeId(item?.bucket || inferSupabaseBucketFromPath(storagePath)),
    });
  }

  if (validItems.length === 0) {
    return jsonResponse({ error: "No authorized files to delete" }, 403, cors);
  }

  let deletedCount = 0;
  if (isR2Active(env)) {
    const keys = validItems.map((i) => i.storagePath);
    await env.CHAT_BUCKET.delete(keys);
    deletedCount = keys.length;
    return jsonResponse({ ok: true, deletedCount }, 200, cors);
  }

  const supabaseBase = getSupabaseBaseUrl(env);
  const grouped = new Map();
  for (const item of validItems) {
    if (!grouped.has(item.bucket)) grouped.set(item.bucket, []);
    grouped.get(item.bucket).push(item.storagePath);
  }
  for (const [bucket, paths] of grouped.entries()) {
    const delRes = await fetch(`${supabaseBase}/storage/v1/object/${encodeURIComponent(bucket)}`, {
      method: "DELETE",
      headers: {
        apikey: env.SUPABASE_SERVICE_ROLE_KEY,
        Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ prefixes: paths }),
    });
    if (delRes.ok) {
      deletedCount += paths.length;
    }
  }
  return jsonResponse({ ok: true, deletedCount }, 200, cors);
}

async function handleSupabaseSignUpload(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors) {
  if (!hasSupabaseConfig(env)) {
    return jsonResponse({ error: "Supabase Storage is not configured" }, 500, cors);
  }
  const bucket = String(body.bucket || "");
  const mimeType = String(body.mimeType || "").toLowerCase();
  const byteSize = Number(body.byteSize || 0);
  const chatId = sanitizeId(body.chatId || "");
  const scope = sanitizeId(body.scope || "");
  const sha256 = String(body.sha256 || "").slice(0, 128);
  const policy = ALLOWED_BUCKETS[bucket];
  if (!policy) return jsonResponse({ error: "Unsupported bucket" }, 400, cors);
  if (byteSize <= 0 || byteSize > policy.maxBytes) {
    return jsonResponse({ error: `File size exceeds free-tier limit (${policy.maxBytes} bytes)` }, 413, cors);
  }
  if (!policy.mimes.includes(mimeType)) {
    return jsonResponse({ error: `Unsupported MIME type: ${mimeType || "unknown"}` }, 415, cors);
  }

  const member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId);
  assertApprovedNotSuspended(member);

  let prefix = "";
  if (bucket === "avatars") {
    prefix = `companies/${companyId}/avatars/${uid}/`;
  } else if (scope === "status" || chatId === "status_feed") {
    if (!["chat-media", "chat-videos", "chat-voice"].includes(bucket)) {
      return jsonResponse({ error: "Unsupported status media bucket" }, 400, cors);
    }
    prefix = `companies/${companyId}/statuses/${uid}/`;
  } else if (bucket === "group-photos") {
    if (!chatId) return jsonResponse({ error: "Missing chatId for group photo" }, 400, cors);
    await assertChatParticipant(companyId, chatId, uid, member, idToken, projectId, firestoreDbId);
    prefix = `companies/${companyId}/group-photos/${chatId}/`;
  } else {
    if (!chatId) return jsonResponse({ error: "Missing chatId for chat attachment" }, 400, cors);
    await assertChatParticipant(companyId, chatId, uid, member, idToken, projectId, firestoreDbId);
    prefix = `companies/${companyId}/chats/${chatId}/`;
  }

  const rawRequestedPath = String(body.storagePath || "").replace(/^\/+/, "");
  const safeName = sanitizeFileName(body.fileName || rawRequestedPath.split("/").pop() || "upload");
  const storagePath = rawRequestedPath && rawRequestedPath.startsWith(prefix)
    ? rawRequestedPath
    : `${prefix}${crypto.randomUUID()}_${safeName}`;
  if (!isSafeStoragePath(storagePath)) {
    return jsonResponse({ error: "Invalid storagePath" }, 400, cors);
  }

  const supabaseBase = getSupabaseBaseUrl(env);
  const signRes = await fetch(
    `${supabaseBase}/storage/v1/object/upload/sign/${encodeURIComponent(bucket)}/${encodeURI(storagePath)}`,
    {
      method: "POST",
      headers: {
        apikey: env.SUPABASE_SERVICE_ROLE_KEY,
        Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ expiresIn: 120 }),
    }
  );
  const signData = await signRes.json().catch(() => ({}));
  if (!signRes.ok || !signData.url) {
    return jsonResponse({ error: signData.message || "Supabase sign-upload failed" }, 502, cors);
  }
  const signedUrl = signData.url.startsWith("http")
    ? signData.url
    : `${supabaseBase}/storage/v1${signData.url}`;
  return jsonResponse(
    {
      ok: true,
      provider: "SUPABASE_STORAGE",
      signedUrl,
      method: "PUT",
      bucket,
      storagePath,
      byteSize,
      mimeType,
      sha256,
      expiresInSec: 120,
    },
    200,
    cors
  );
}

async function handleSupabaseSignDownload(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors) {
  if (!hasSupabaseConfig(env)) {
    return jsonResponse({ error: "Supabase Storage is not configured" }, 500, cors);
  }
  const storagePath = String(body.storagePath || "").replace(/^\/+/, "");
  const bucket = sanitizeId(body.bucket || inferSupabaseBucketFromPath(storagePath));
  const chatId = sanitizeId(body.chatId || "");
  const statusId = sanitizeId(body.statusId || "");
  if (!ALLOWED_BUCKETS[bucket] || !isSafeStoragePath(storagePath)) {
    return jsonResponse({ error: "Invalid download target" }, 400, cors);
  }

  const member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId);
  assertApprovedNotSuspended(member);

  const isStatusMedia = storagePath.startsWith(`companies/${companyId}/statuses/`);
  const isAvatar = storagePath.startsWith(`companies/${companyId}/avatars/`);
  const isGroupPhoto = storagePath.startsWith(`companies/${companyId}/group-photos/`);

  if (isStatusMedia) {
    if (!statusId) {
      return jsonResponse({ error: "Missing statusId for status media" }, 400, cors);
    }
    const stUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}/chat_statuses/${statusId}`;
    const stRes = await fetch(stUrl, { headers: { Authorization: `Bearer ${idToken}` } });
    if (!stRes.ok) {
      return jsonResponse({ error: "Status access denied or unavailable" }, stRes.status === 404 ? 404 : 403, cors);
    }
    const stDoc = parseFirestoreDoc(await stRes.json());
    if (stDoc.expiresAtMillis && Number(stDoc.expiresAtMillis) < Date.now()) {
      return jsonResponse({ error: "Status has expired" }, 410, cors);
    }
    if (stDoc.ownerUid && stDoc.ownerUid !== uid) {
      const blocked = Array.isArray(stDoc.ownerBlockedUids) ? stDoc.ownerBlockedUids : [];
      if (blocked.includes(uid)) {
        return jsonResponse({ error: "Blocked user cannot view status media" }, 403, cors);
      }
      const mode = stDoc.privacyMode || "ALL_APPROVED";
      const allowed = Array.isArray(stDoc.allowedUids) ? stDoc.allowedUids : [];
      const excluded = Array.isArray(stDoc.excludedUids) ? stDoc.excludedUids : [];
      if (mode === "SELECTED_ONLY" && !allowed.includes(uid)) {
        return jsonResponse({ error: "You do not have permission to view this status" }, 403, cors);
      }
      if (mode === "ALL_EXCEPT" && excluded.includes(uid)) {
        return jsonResponse({ error: "You do not have permission to view this status" }, 403, cors);
      }
    }
  } else if (isGroupPhoto) {
    const inferredGroupChatId = storagePath
      .slice(`companies/${companyId}/group-photos/`.length)
      .split("/")[0] || "";
    const resolvedChatId = chatId || sanitizeId(inferredGroupChatId);
    if (!resolvedChatId) {
      return jsonResponse({ error: "Missing chatId for group photo" }, 400, cors);
    }
    const expectedGroupPrefix = `companies/${companyId}/group-photos/${resolvedChatId}/`;
    if (!storagePath.startsWith(expectedGroupPrefix)) {
      return jsonResponse({ error: "Path mismatch for group photo" }, 403, cors);
    }
    await assertChatParticipant(companyId, resolvedChatId, uid, member, idToken, projectId, firestoreDbId);
  } else if (!isAvatar) {
    if (!chatId) return jsonResponse({ error: "Missing chatId" }, 400, cors);
    const expectedPrefix = `companies/${companyId}/chats/${chatId}/`;
    if (!storagePath.startsWith(expectedPrefix)) {
      return jsonResponse({ error: "Path mismatch for chat attachment" }, 403, cors);
    }
    await assertChatParticipant(companyId, chatId, uid, member, idToken, projectId, firestoreDbId);
  }

  const supabaseBase = getSupabaseBaseUrl(env);
  const signRes = await fetch(
    `${supabaseBase}/storage/v1/object/sign/${encodeURIComponent(bucket)}/${encodeURI(storagePath)}`,
    {
      method: "POST",
      headers: {
        apikey: env.SUPABASE_SERVICE_ROLE_KEY,
        Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ expiresIn: 300 }),
    }
  );
  const signData = await signRes.json().catch(() => ({}));
  if (!signRes.ok || !signData.signedURL) {
    return jsonResponse({ error: signData.message || "Supabase sign-download failed" }, 502, cors);
  }
  const signedUrl = signData.signedURL.startsWith("http")
    ? signData.signedURL
    : `${supabaseBase}/storage/v1${signData.signedURL}`;
  return jsonResponse({ signedUrl, provider: "SUPABASE_STORAGE", bucket, expiresInSec: 300 }, 200, cors);
}

async function handlePushDispatch(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors) {
  const chatId = sanitizeId(body.chatId || "");
  if (!chatId) return jsonResponse({ error: "Missing chatId" }, 400, cors);

  const member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId);
  assertApprovedNotSuspended(member);

  const { chat } = await assertChatParticipant(
    companyId,
    chatId,
    uid,
    member,
    idToken,
    projectId,
    firestoreDbId
  );
  const channelParticipants = Array.isArray(chat.participantUids) ? chat.participantUids : [];
  const rawTargets = Array.isArray(body.targetUids)
    ? body.targetUids.map(sanitizeId).filter((p) => p && p !== uid).slice(0, 35)
    : [];
  const targetUids = channelParticipants.length > 0
    ? rawTargets.filter((p) => channelParticipants.includes(p))
    : rawTargets;

  if (targetUids.length === 0) return jsonResponse({ sentCount: 0 }, 200, cors);
  if (!env.SERVICE_ACCOUNT_JSON && !env.FIREBASE_SERVICE_ACCOUNT_JSON) {
    return jsonResponse({ sentCount: 0, skipped: "SERVICE_ACCOUNT_JSON secret not set" }, 200, cors);
  }

  const tokens = [];
  for (const recipientId of targetUids) {
    const tokenListUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}/deviceTokens/${recipientId}/tokens?pageSize=5`;
    const tRes = await fetch(tokenListUrl, { headers: { Authorization: `Bearer ${idToken}` } });
    if (tRes.ok) {
      const data = await tRes.json();
      for (const doc of data.documents || []) {
        const parsed = parseFirestoreDoc(doc);
        if (parsed.token) tokens.push(parsed.token);
      }
    }
  }

  if (tokens.length === 0) return jsonResponse({ sentCount: 0 }, 200, cors);

  const accessToken = await getGoogleOAuthAccessToken(env);
  let sentCount = 0;

  for (const token of tokens.slice(0, 35)) {
    const fcmPayload = {
      message: {
        token,
        notification: {
          title: String(body.title || chat.title || chat.name || "X.MAX Chat").slice(0, 120),
          body: String(body.body || "New attachment or message").slice(0, 240),
        },
        data: {
          companyId,
          chatId,
          businessKind: String(body.businessKind || ""),
          businessId: String(body.businessId || ""),
        },
      },
    };

    const fcmRes = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(fcmPayload),
    });
    if (fcmRes.ok) sentCount++;
  }

  return jsonResponse({ sentCount }, 200, cors);
}

async function handleResetChunk(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors) {
  const member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId);
  assertApprovedNotSuspended(member);
  const accessLevel = member.accessLevel || member.role;
  if (accessLevel !== "SUPER_ADMIN") {
    return jsonResponse({ error: "Only Super Admin can perform chat reset operations" }, 403, cors);
  }

  const chatId = sanitizeId(body.chatId || "");
  if (!chatId) return jsonResponse({ error: "Missing chatId for reset" }, 400, cors);

  let deletedStorageInChunk = 0;
  if (isR2Active(env)) {
    const prefixes = [
      `companies/${companyId}/chats/${chatId}/`,
      `companies/${companyId}/group-photos/${chatId}/`,
    ];
    for (const prefix of prefixes) {
      const listed = await env.CHAT_BUCKET.list({ prefix, limit: 200 });
      const keys = listed.objects.map((obj) => obj.key);
      if (keys.length > 0) {
        await env.CHAT_BUCKET.delete(keys);
        deletedStorageInChunk += keys.length;
      }
    }
  } else if (hasSupabaseConfig(env)) {
    const supabaseBase = getSupabaseBaseUrl(env);
    const prefixes = [
      `companies/${companyId}/chats/${chatId}`,
      `companies/${companyId}/group-photos/${chatId}`,
    ];
    for (const bucket of Object.keys(ALLOWED_BUCKETS)) {
      for (const prefix of prefixes) {
        const listRes = await fetch(`${supabaseBase}/storage/v1/object/list/${encodeURIComponent(bucket)}`, {
          method: "POST",
          headers: {
            apikey: env.SUPABASE_SERVICE_ROLE_KEY,
            Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ prefix, limit: 100, offset: 0 }),
        }).catch(() => null);
        if (!listRes || !listRes.ok) continue;
        const entries = await listRes.json().catch(() => []);
        const paths = (Array.isArray(entries) ? entries : [])
          .filter((e) => e && e.id && e.name)
          .map((e) => `${prefix}/${e.name}`);
        if (paths.length === 0) continue;
        const delRes = await fetch(`${supabaseBase}/storage/v1/object/${encodeURIComponent(bucket)}`, {
          method: "DELETE",
          headers: {
            apikey: env.SUPABASE_SERVICE_ROLE_KEY,
            Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ prefixes: paths }),
        }).catch(() => null);
        if (delRes && delRes.ok) {
          deletedStorageInChunk += paths.length;
        }
      }
    }
  }

  let deletedDocsInChunk = 0;
  for (const col of CHAT_RESET_COLLECTIONS) {
    const listUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}/chat_channels/${chatId}/${col}?pageSize=100`;
    const listRes = await fetch(listUrl, { headers: { Authorization: `Bearer ${idToken}` } });
    if (listRes.ok) {
      const data = await listRes.json();
      const docs = data.documents || [];
      for (const doc of docs) {
        const delRes = await fetch(`https://firestore.googleapis.com/v1/${doc.name}`, {
          method: "DELETE",
          headers: { Authorization: `Bearer ${idToken}` },
        });
        if (delRes.ok) deletedDocsInChunk++;
      }
    }
  }

  const chatDocUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}/chat_channels/${chatId}`;
  await fetch(chatDocUrl, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${idToken}` },
  });

  return jsonResponse(
    {
      completed: true,
      nextCollection: "",
      nextCursorDocId: "",
      deletedDocsInChunk,
      deletedStorageInChunk,
    },
    200,
    cors
  );
}

async function getGoogleOAuthAccessToken(env) {
  const sa = JSON.parse(env.SERVICE_ACCOUNT_JSON || env.FIREBASE_SERVICE_ACCOUNT_JSON || "{}");
  if (!sa.private_key || !sa.client_email) throw new Error("Missing SERVICE_ACCOUNT_JSON secret");

  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "RS256", typ: "JWT" };
  const payload = {
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging https://www.googleapis.com/auth/datastore",
    aud: "https://oauth2.googleapis.com/token",
    exp: now + 3600,
    iat: now,
  };

  const encodedHeader = btoa(JSON.stringify(header)).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  const encodedPayload = btoa(JSON.stringify(payload)).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  const signInput = `${encodedHeader}.${encodedPayload}`;

  const pemContents = sa.private_key
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s/g, "");
  const binaryDer = Uint8Array.from(atob(pemContents), (c) => c.charCodeAt(0));

  const key = await crypto.subtle.importKey(
    "pkcs8",
    binaryDer.buffer,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(signInput));
  const encodedSig = btoa(String.fromCharCode(...new Uint8Array(signature)))
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
  const jwt = `${signInput}.${encodedSig}`;

  const tokenRes = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${jwt}`,
  });

  const tokenData = await tokenRes.json();
  if (!tokenData.access_token) throw new Error("Failed to obtain Google OAuth access token");
  return tokenData.access_token;
}

function escapeHtml(raw) {
  return String(raw || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function formatInrPaise(paise) {
  const num = Number(paise || 0) / 100;
  return "₹" + num.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

async function fetchDocAsService(path, projectId, firestoreDbId, accessToken) {
  const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/${path}`;
  const res = await fetch(docUrl, { headers: { Authorization: `Bearer ${accessToken}` } });
  if (!res.ok) return null;
  const raw = await res.json();
  return parseFirestoreDoc(raw);
}

async function queryProjectDeliverablesAsService(companyId, projId, projectId, firestoreDbId, accessToken) {
  const queryUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}:runQuery`;
  const res = await fetch(queryUrl, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      structuredQuery: {
        from: [{ collectionId: "deliverables" }],
        where: {
          fieldFilter: {
            field: { fieldPath: "projectId" },
            op: "EQUAL",
            value: { stringValue: projId },
          },
        },
        limit: 100,
      },
    }),
  });
  if (!res.ok) return [];
  const rows = await res.json().catch(() => []);
  if (!Array.isArray(rows)) return [];
  return rows.filter((r) => r && r.document).map((r) => parseFirestoreDoc(r.document));
}

async function handlePublicPortalRoute(request, url, projectId, firestoreDbId, env, cors) {
  const clientIp = request.headers.get("CF-Connecting-IP") || "global";
  if (!checkRateLimit(`portal:${clientIp}:${url.pathname}`, 30, 60_000)) {
    return jsonResponse({ error: "Rate limit exceeded" }, 429, { ...cors, "Retry-After": "30" });
  }

  const htmlSecurityHeaders = {
    "Content-Type": "text/html; charset=utf-8",
    "Content-Security-Policy":
      "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    ...cors,
  };

  const parts = url.pathname.split("/").filter(Boolean); // ["portal", token, sub?]
  const token = sanitizeId(parts[1] || "");
  const sub = parts[2] || "";
  if (!token || token.length < 12) {
    return jsonResponse({ error: "Invalid portal token" }, 400, cors);
  }
  if (!env.SERVICE_ACCOUNT_JSON && !env.FIREBASE_SERVICE_ACCOUNT_JSON) {
    return new Response(
      `<html><body style="font-family:sans-serif;padding:32px;max-width:640px;margin:auto"><h2>X.MAX Client Portal</h2><p>Portal backend credentials are not configured yet on the Worker. Contact your account manager.</p></body></html>`,
      { status: 503, headers: htmlSecurityHeaders }
    );
  }

  const accessToken = await getGoogleOAuthAccessToken(env);
  const linkDoc = await fetchDocAsService(`portal_tokens/${token}`, projectId, firestoreDbId, accessToken);
  if (!linkDoc || linkDoc.revoked === true) {
    return jsonResponse({ error: "Client portal link not found or revoked" }, 404, cors);
  }
  if (linkDoc.expiresAtMillis && Number(linkDoc.expiresAtMillis) < Date.now()) {
    return jsonResponse({ error: "Client portal link has expired" }, 410, cors);
  }

  const companyId = sanitizeId(linkDoc.companyId || "xmax");
  const projId = sanitizeId(linkDoc.projectId || "");
  const quoteId = sanitizeId(linkDoc.quotationId || "");

  const [company, project, quotation, projectDeliverableDocs] = await Promise.all([
    fetchDocAsService(`companies/${companyId}`, projectId, firestoreDbId, accessToken),
    projId ? fetchDocAsService(`companies/${companyId}/projects/${projId}`, projectId, firestoreDbId, accessToken) : Promise.resolve(null),
    quoteId ? fetchDocAsService(`companies/${companyId}/quotations/${quoteId}`, projectId, firestoreDbId, accessToken) : Promise.resolve(null),
    projId ? queryProjectDeliverablesAsService(companyId, projId, projectId, firestoreDbId, accessToken) : Promise.resolve([]),
  ]);

  if (request.method === "POST" && sub === "approve") {
    const body = await request.json().catch(() => ({}));
    const note = String(body.note || "Approved via Client Portal link").slice(0, 400);
    const patchUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}/client_portal_links/${token}?updateMask.fieldPaths=clientApprovedAtMillis&updateMask.fieldPaths=clientApprovalNote`;
    await fetch(patchUrl, {
      method: "PATCH",
      headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        fields: {
          clientApprovedAtMillis: { integerValue: String(Date.now()) },
          clientApprovalNote: { stringValue: note },
        },
      }),
    });
    await fetch(
      `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/portal_tokens/${token}?updateMask.fieldPaths=clientApprovedAtMillis&updateMask.fieldPaths=clientApprovalNote`,
      {
        method: "PATCH",
        headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          fields: {
            clientApprovedAtMillis: { integerValue: String(Date.now()) },
            clientApprovalNote: { stringValue: note },
          },
        }),
      }
    );
    return jsonResponse({ ok: true, approvedAtMillis: Date.now(), note }, 200, cors);
  }

  const projectDeliverables = (projectDeliverableDocs || [])
    .filter((d) => d.projectId === projId)
    .map((d) => ({ title: d.title || "Deliverable", status: d.status || "NOT_STARTED", progressPercent: Number(d.progressPercent || 0) }));

  const safePayload = {
    companyName: company?.name || "X.MAX Agency",
    projectTitle: project?.title || linkDoc.projectTitle || quotation?.projectTitle || "Project",
    clientName: project?.clientCompanyName || linkDoc.clientName || "",
    status: project?.status || quotation?.status || "ACTIVE",
    agreedValuePaise: Number(project?.agreedValuePaise || quotation?.totalPaise || 0),
    paidPaise: Number(project?.paidPaise || 0),
    gstin: company?.gstin || "",
    gstRatePercent: Number(company?.gstRatePercent || 18),
    deliverables: projectDeliverables,
    approvedAtMillis: Number(linkDoc.clientApprovedAtMillis || 0),
    approvalNote: linkDoc.clientApprovalNote || "",
  };

  if (sub === "invoice") {
    const balance = Math.max(0, safePayload.agreedValuePaise - safePayload.paidPaise);
    const html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>GST Tax Invoice</title><style>body{font-family:system-ui,sans-serif;max-width:720px;margin:24px auto;padding:20px;color:#0f172a} .box{border:1px solid #cbd5e1;border-radius:14px;padding:18px;background:#f8fafc}</style></head><body><h1>${escapeHtml(safePayload.companyName)} — Tax Invoice</h1><div class="box"><p><b>Client:</b> ${escapeHtml(safePayload.clientName)}</p><p><b>Project:</b> ${escapeHtml(safePayload.projectTitle)}</p><p><b>GSTIN:</b> ${escapeHtml(safePayload.gstin || "Registered")}</p><p><b>Total Contract:</b> ${formatInrPaise(safePayload.agreedValuePaise)}</p><p><b>Paid:</b> ${formatInrPaise(safePayload.paidPaise)}</p><p><b>Balance Due:</b> ${formatInrPaise(balance)}</p><button onclick="window.print()" style="margin-top:12px;padding:10px 18px;border-radius:999px;border:none;background:#111;color:#fff;cursor:pointer">Download / Print GST Invoice</button></div></body></html>`;
    return new Response(html, { status: 200, headers: htmlSecurityHeaders });
  }

  const accept = request.headers.get("Accept") || "";
  if (accept.includes("application/json") || url.searchParams.get("format") === "json") {
    return jsonResponse(safePayload, 200, cors);
  }

  const rowsHtml = projectDeliverables
    .map((d) => `<li><b>${escapeHtml(d.title)}</b> — ${escapeHtml(d.status)} (${d.progressPercent}%)</li>`)
    .join("");
  const html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(safePayload.projectTitle)} Portal</title><style>body{font-family:system-ui,sans-serif;max-width:740px;margin:24px auto;padding:20px;background:#f8fafc;color:#0f172a}.card{background:#fff;border:1px solid #e2e8f0;border-radius:16px;padding:20px;margin-bottom:16px}</style></head><body><div class="card"><h2>${escapeHtml(safePayload.companyName)} — Client Portal</h2><p><b>Project:</b> ${escapeHtml(safePayload.projectTitle)} (${escapeHtml(safePayload.status)})</p><p><b>Client:</b> ${escapeHtml(safePayload.clientName)}</p><p><b>Total:</b> ${formatInrPaise(safePayload.agreedValuePaise)} | <b>Paid:</b> ${formatInrPaise(safePayload.paidPaise)}</p><a href="/portal/${escapeHtml(token)}/invoice">View GST Tax Invoice</a></div><div class="card"><h3>Deliverables</h3><ul>${rowsHtml || "<li>In progress</li>"}</ul></div><div class="card"><button onclick="fetch('/portal/${escapeHtml(token)}/approve',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({note:'Approved milestone via Portal'})}).then(()=>location.reload())" style="padding:10px 18px;border-radius:999px;border:none;background:#4f46e5;color:#fff;cursor:pointer">Approve Milestone</button>${safePayload.approvedAtMillis ? `<p>Approved: ${escapeHtml(safePayload.approvalNote)}</p>` : ""}</div></body></html>`;
  return new Response(html, { status: 200, headers: htmlSecurityHeaders });
}

async function handlePaymentNudgeDispatch(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors) {
  const member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId);
  assertApprovedNotSuspended(member);

  const accessLevel = String(member.accessLevel || member.role || "").toUpperCase();
  const perms = member.permissions || {};
  const grants = member.delegatedGrants || {};
  const hasPerm = (key) => Boolean(perms?.[key]?.allowed || grants?.[key]?.allowed);
  const canNudge =
    accessLevel === "SUPER_ADMIN" ||
    accessLevel === "ADMIN" ||
    hasPerm("RECORD_PAYMENTS") ||
    hasPerm("VIEW_PAYMENTS") ||
    hasPerm("ISSUE_QUOTATIONS");
  if (!canNudge) {
    return jsonResponse({ error: "Insufficient commercial permissions to dispatch client payment nudges" }, 403, cors);
  }

  const phone = String(body.phone || "").replace(/[^0-9+]/g, "");
  const email = String(body.email || "").trim();
  const messageBody = String(body.messageBody || "").trim();
  const channels = String(body.channels || "WHATSAPP_AND_EMAIL");

  let whatsappDispatched = false;
  let emailDispatched = false;

  if ((channels.includes("WHATSAPP")) && phone && env.WHATSAPP_TOKEN && env.WHATSAPP_PHONE_ID) {
    const waRes = await fetch(`https://graph.facebook.com/v19.0/${env.WHATSAPP_PHONE_ID}/messages`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.WHATSAPP_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        messaging_product: "whatsapp",
        to: phone.replace(/^\+/, ""),
        type: "text",
        text: { body: messageBody },
      }),
    }).catch(() => null);
    if (waRes && waRes.ok) whatsappDispatched = true;
  }

  if ((channels.includes("EMAIL")) && email && env.EMAIL_WEBHOOK_URL) {
    const emRes = await fetch(String(env.EMAIL_WEBHOOK_URL), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        to: email,
        subject: `Payment Reminder — ${String(body.clientName || "Project")} (${String(body.amountDueFormatted || "")})`,
        text: messageBody,
        portalUrl: String(body.portalUrl || ""),
      }),
    }).catch(() => null);
    if (emRes && emRes.ok) emailDispatched = true;
  }

  const mode = (whatsappDispatched || emailDispatched) ? "CLOUD_API" : "LINK_FALLBACK";
  return jsonResponse(
    {
      ok: true,
      mode,
      whatsappDispatched,
      emailDispatched,
      note: mode === "CLOUD_API"
        ? "Dispatched via configured Worker API."
        : "No WhatsApp/Email Worker secrets configured; logged nudge and prepared device handoff links.",
    },
    200,
    cors
  );
}
