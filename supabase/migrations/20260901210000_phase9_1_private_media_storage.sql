INSERT INTO storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
VALUES ('player2-media', 'player2-media', FALSE, 524288000, ARRAY['video/mp4','image/jpeg','image/png','image/webp'])
ON CONFLICT (id) DO UPDATE SET public = FALSE;

-- Storage remains service-only in Phase 9.1. The Player receives a short-lived URL
-- only after player-media validates its credential and current screen assignment.
DROP POLICY IF EXISTS player2_media_public_read ON storage.objects;
REVOKE ALL ON TABLE storage.objects FROM anon;
