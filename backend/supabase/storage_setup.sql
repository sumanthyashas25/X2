-- X.MAX Zero-Cost Supabase Storage Bucket Provisioning & Firebase Third-Party Auth Policies
-- Run in the Supabase SQL Editor. All buckets are PRIVATE (public = false).
-- Supports both:
--   1. Cloudflare Worker backend access via service_role
--   2. Direct Firebase Auth Third-Party JWT access (Supabase Auth -> Third-Party Auth -> Firebase)

INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES
  (
    'avatars',
    'avatars',
    false,
    2097152,
    ARRAY['image/jpeg', 'image/png', 'image/webp']
  ),
  (
    'group-photos',
    'group-photos',
    false,
    2097152,
    ARRAY['image/jpeg', 'image/png', 'image/webp']
  ),
  (
    'chat-media',
    'chat-media',
    false,
    5242880,
    ARRAY['image/jpeg', 'image/png', 'image/webp', 'image/gif']
  ),
  (
    'chat-voice',
    'chat-voice',
    false,
    3145728,
    ARRAY['audio/mp4', 'audio/aac', 'audio/m4a', 'audio/x-m4a', 'audio/mpeg', 'audio/wav', 'audio/ogg', 'audio/3gpp', 'audio/amr']
  ),
  (
    'chat-videos',
    'chat-videos',
    false,
    15728640,
    ARRAY['video/mp4', 'video/webm', 'video/quicktime', 'video/3gpp', 'image/jpeg']
  ),
  (
    'chat-docs',
    'chat-docs',
    false,
    10485760,
    ARRAY[
      'application/pdf',
      'text/plain',
      'text/csv',
      'application/msword',
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      'application/vnd.ms-excel',
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      'application/vnd.ms-powerpoint',
      'application/vnd.openxmlformats-officedocument.presentationml.presentation',
      'application/zip',
      'application/x-zip-compressed'
    ]
  )
ON CONFLICT (id) DO UPDATE SET
  public = EXCLUDED.public,
  file_size_limit = EXCLUDED.file_size_limit,
  allowed_mime_types = EXCLUDED.allowed_mime_types;

-- Remove overly restrictive blanket policy that previously blocked even authenticated Firebase JWT users.
DROP POLICY IF EXISTS "Deny public direct storage reads" ON storage.objects;
DROP POLICY IF EXISTS "Deny anonymous storage access" ON storage.objects;
DROP POLICY IF EXISTS "Allow authenticated XMAX storage read" ON storage.objects;
DROP POLICY IF EXISTS "Allow authenticated XMAX storage insert" ON storage.objects;
DROP POLICY IF EXISTS "Allow authenticated XMAX storage delete" ON storage.objects;

-- 1. Block all unauthenticated / anon access.
CREATE POLICY "Deny anonymous storage access"
  ON storage.objects
  FOR ALL
  TO anon
  USING (false)
  WITH CHECK (false);

-- 2. Allow authenticated users (via Supabase Firebase Third-Party Auth JWT) to read/upload objects in X.MAX buckets.
CREATE POLICY "Allow authenticated XMAX storage read"
  ON storage.objects
  FOR SELECT
  TO authenticated
  USING (
    bucket_id IN ('avatars', 'group-photos', 'chat-media', 'chat-voice', 'chat-videos', 'chat-docs')
    AND coalesce(auth.jwt() ->> 'sub', '') <> ''
  );

CREATE POLICY "Allow authenticated XMAX storage insert"
  ON storage.objects
  FOR INSERT
  TO authenticated
  WITH CHECK (
    bucket_id IN ('avatars', 'group-photos', 'chat-media', 'chat-voice', 'chat-videos', 'chat-docs')
    AND coalesce(auth.jwt() ->> 'sub', '') <> ''
  );

CREATE POLICY "Allow authenticated XMAX storage delete"
  ON storage.objects
  FOR DELETE
  TO authenticated
  USING (
    bucket_id IN ('avatars', 'group-photos', 'chat-media', 'chat-voice', 'chat-videos', 'chat-docs')
    AND coalesce(auth.jwt() ->> 'sub', '') <> ''
  );
