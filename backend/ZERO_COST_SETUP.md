# X.MAX Free-Tier Firebase + Cloudflare Worker + Supabase Storage Setup

This workspace integrates **Firebase Spark Plan (Free)** with **Cloudflare Workers Free Plan** and **Supabase Storage Free Plan** (plus optional **Cloudflare R2 Free Tier**), matching the exact architecture:

`X.MAX Android App -> Cloudflare Worker (Secure Backend) -> Firebase (Users, messages, data) + Supabase (Photos, videos, files)`

1. **Firebase Spark Plan (₹0)**:
   - Authentication (Google Sign-In via Credential Manager)
   - Cloud Firestore (named DB `ai-studio-android-x2-2e94d6cb-2e92-47e1-acbe-98bc9d3528ed` storing users, chat channels, messages, and storage path metadata)
   - Firebase Realtime Database (ephemeral typing/recording/presence indicators)
2. **Cloudflare Workers Free Plan ($0 / ₹0)**:
   - `100,000` requests/day, `10 ms` CPU time per request
   - Verifies Firebase Auth ID tokens (`Authorization: Bearer <token>`) against Google public JWKs
   - Verifies Firestore membership (`APPROVED` & active) and `chat_channels` / `chat_statuses` permissions before issuing short-lived Supabase signed upload/download URLs (or streaming to R2)
3. **Supabase Storage Free Tier ($0, 1 GB storage, 5 GB cached/2 GB uncached egress)**:
   - Keeps photos (up to 5 MB), voice notes (up to 3 MB), documents (up to 10 MB), and status videos (up to 15 MB) outside Firestore
   - Android uses **direct signed URL transfers** (`/v1/storage/sign-upload` -> HTTP `PUT` to Supabase, `/v1/storage/sign-download` -> HTTP `GET` from Supabase) so the Cloudflare Worker stays under its 10 ms free-tier CPU limit
   - Small attachments (`<= 520 KB`) automatically fall back to Firestore inline storage if the Worker URL is not configured or offline.

## Deployment Steps (Zero Secrets in the Android APK)

1. **Run `backend/supabase/storage_setup.sql`** inside your Supabase project's **SQL Editor** to create the private storage buckets (`avatars`, `group-photos`, `chat-media`, `chat-voice`, `chat-videos`, `chat-docs`).
2. **Authenticate Wrangler with Cloudflare** (run from `backend/cloudflare-worker/`):
   ```bash
   npx wrangler login
   ```
3. **Configure Storage Backend (Supabase Free Tier or Cloudflare R2)**:
   - For **Supabase Storage** (remove or leave `[[r2_buckets]]` unbound if prioritizing Supabase):
     ```bash
     npx wrangler secret put SUPABASE_URL
     npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY
     ```
   - Or for **Cloudflare R2**:
     ```bash
     npx wrangler r2 bucket create xmax-chat-files
     ```
4. **Deploy the Worker**:
   ```bash
   npx wrangler deploy
   ```
   Verify health at `https://<your-worker>.workers.dev/v1/health`:
   It returns `"storageProvider": "SUPABASE_STORAGE"` (when Supabase secrets are configured without R2 binding) or `"CLOUDFLARE_R2"`.

5. **Point the Android App to Your Worker URL**:
   - In the AI Studio **Secrets** panel, set `CHAT_WORKER_BASE_URL` to your deployed `https://<your-worker>.workers.dev` URL (no trailing slash).
   - No Supabase service role key, R2 key, or FCM key is ever placed in the Android APK or `.env`.
