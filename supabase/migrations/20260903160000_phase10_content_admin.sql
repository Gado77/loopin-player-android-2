-- Phase 10: tenant-scoped media upload, mutable drafts and immutable publication.
CREATE TABLE public.player_playlist_drafts (
    playlist_id UUID PRIMARY KEY REFERENCES public.player_playlists(id) ON DELETE CASCADE,
    items JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(items) = 'array'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.player_playlist_drafts ENABLE ROW LEVEL SECURITY;
CREATE POLICY playlist_drafts_select_own ON public.player_playlist_drafts FOR SELECT TO authenticated USING (
    EXISTS (SELECT 1 FROM public.player_playlists p WHERE p.id = playlist_id AND p.owner_id = auth.uid())
);
REVOKE ALL ON public.player_playlist_drafts FROM anon;
GRANT SELECT ON public.player_playlist_drafts TO authenticated;

-- Browser upload is insert-only and restricted to users/<auth.uid()>/<uuid>/original.ext.
GRANT INSERT ON storage.objects TO authenticated;
CREATE POLICY player2_media_insert_own_namespace ON storage.objects FOR INSERT TO authenticated WITH CHECK (
    bucket_id = 'player2-media'
    AND (storage.foldername(name))[1] = 'users'
    AND (storage.foldername(name))[2] = auth.uid()::text
    AND (storage.foldername(name))[3] ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    AND name ~ ('^users/' || auth.uid()::text || '/[0-9a-f-]{36}/original\.(mp4|jpg|jpeg|png)$')
);

CREATE OR REPLACE FUNCTION public.register_player_media_asset(
    p_asset_id UUID, p_name TEXT, p_media_type TEXT, p_expected_size_bytes BIGINT,
    p_sha256 TEXT, p_mime_type TEXT
) RETURNS public.player_media_assets
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, storage
AS $$
DECLARE
    v_owner UUID := auth.uid(); v_extension TEXT; v_path TEXT;
    v_object storage.objects%ROWTYPE; v_existing public.player_media_assets%ROWTYPE;
    v_result public.player_media_assets%ROWTYPE;
BEGIN
    IF v_owner IS NULL THEN RAISE EXCEPTION 'authentication required'; END IF;
    IF length(trim(p_name)) NOT BETWEEN 1 AND 200 THEN RAISE EXCEPTION 'invalid asset name'; END IF;
    IF p_media_type NOT IN ('VIDEO','IMAGE') OR p_expected_size_bytes <= 0
       OR p_sha256 !~ '^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'invalid asset metadata'; END IF;
    IF (p_media_type = 'VIDEO' AND p_mime_type <> 'video/mp4')
       OR (p_media_type = 'IMAGE' AND p_mime_type NOT IN ('image/jpeg','image/png'))
    THEN RAISE EXCEPTION 'unsupported media type'; END IF;
    IF (p_media_type = 'VIDEO' AND p_expected_size_bytes > 314572800)
       OR (p_media_type = 'IMAGE' AND p_expected_size_bytes > 20971520)
    THEN RAISE EXCEPTION 'asset exceeds size limit'; END IF;
    SELECT * INTO v_existing FROM public.player_media_assets WHERE owner_id = v_owner AND sha256 = p_sha256;
    IF FOUND THEN RETURN v_existing; END IF;
    v_extension := CASE p_mime_type WHEN 'video/mp4' THEN 'mp4' WHEN 'image/png' THEN 'png' ELSE 'jpg' END;
    v_path := 'users/' || v_owner || '/' || p_asset_id || '/original.' || v_extension;
    SELECT * INTO v_object FROM storage.objects WHERE bucket_id = 'player2-media' AND name = v_path;
    IF NOT FOUND THEN RAISE EXCEPTION 'uploaded object unavailable'; END IF;
    IF COALESCE((v_object.metadata->>'size')::bigint, -1) <> p_expected_size_bytes
       OR COALESCE(v_object.metadata->>'mimetype','') <> p_mime_type
    THEN RAISE EXCEPTION 'uploaded object metadata mismatch'; END IF;
    INSERT INTO public.player_media_assets(id, owner_id, name, media_type, expected_size_bytes, sha256, mime_type, storage_path)
    VALUES (p_asset_id, v_owner, trim(p_name), p_media_type, p_expected_size_bytes, p_sha256, p_mime_type, v_path)
    RETURNING * INTO v_result;
    RETURN v_result;
END; $$;

CREATE OR REPLACE FUNCTION public.save_player_playlist_draft(p_playlist_id UUID, p_items JSONB)
RETURNS public.player_playlist_drafts
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE v_owner UUID := auth.uid(); v_result public.player_playlist_drafts%ROWTYPE;
    v_lat DOUBLE PRECISION; v_lon DOUBLE PRECISION;
BEGIN
    IF v_owner IS NULL THEN RAISE EXCEPTION 'authentication required'; END IF;
    PERFORM 1 FROM public.player_playlists WHERE id = p_playlist_id AND owner_id = v_owner;
    IF NOT FOUND THEN RAISE EXCEPTION 'playlist unavailable'; END IF;
    IF jsonb_typeof(p_items) <> 'array' OR jsonb_array_length(p_items) > 200 THEN RAISE EXCEPTION 'invalid draft'; END IF;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_items) item WHERE
        NOT item ?& ARRAY['id','order','kind'] OR length(trim(COALESCE(item->>'id',''))) = 0
        OR COALESCE((item->>'order')::int,-1) < 0 OR item->>'kind' NOT IN ('MEDIA','DYNAMIC')
        OR (item->>'kind'='MEDIA' AND (NOT item ? 'assetId' OR EXISTS (SELECT 1 FROM jsonb_object_keys(item) k WHERE k NOT IN ('id','order','kind','assetId','durationMs'))))
        OR (item->>'kind'='DYNAMIC' AND (NOT item ?& ARRAY['dynamicType','durationMs','configuration'] OR item->>'dynamicType'<>'WEATHER'
          OR COALESCE((item->>'durationMs')::bigint,0) NOT BETWEEN 1000 AND 3600000
          OR jsonb_typeof(item->'configuration')<>'object' OR NOT (item->'configuration') ?& ARRAY['city','lat','lon']
          OR EXISTS (SELECT 1 FROM jsonb_object_keys(item->'configuration') k WHERE k NOT IN ('city','lat','lon'))
          OR length(trim(COALESCE(item->'configuration'->>'city',''))) = 0))
    ) THEN RAISE EXCEPTION 'invalid draft item'; END IF;
    IF (SELECT count(*) FROM jsonb_array_elements(p_items)) <> (SELECT count(DISTINCT i->>'id') FROM jsonb_array_elements(p_items) i)
       OR (SELECT count(*) FROM jsonb_array_elements(p_items)) <> (SELECT count(DISTINCT i->>'order') FROM jsonb_array_elements(p_items) i)
    THEN RAISE EXCEPTION 'duplicate draft item'; END IF;
    IF jsonb_array_length(p_items) > 0 AND
       (SELECT max((i->>'order')::int) FROM jsonb_array_elements(p_items) i) <> jsonb_array_length(p_items) - 1
    THEN RAISE EXCEPTION 'draft order must be contiguous'; END IF;
    IF EXISTS (
        SELECT 1 FROM jsonb_array_elements(p_items) item
        LEFT JOIN public.player_media_assets asset
          ON item->>'kind'='MEDIA' AND asset.id=(item->>'assetId')::uuid AND asset.owner_id=v_owner
        WHERE item->>'kind'='MEDIA' AND (
          asset.id IS NULL OR (asset.media_type='IMAGE' AND COALESCE((item->>'durationMs')::bigint,0) NOT BETWEEN 1000 AND 3600000)
        )
    ) THEN RAISE EXCEPTION 'media asset unavailable or duration invalid'; END IF;
    FOR v_lat, v_lon IN SELECT (item->'configuration'->>'lat')::double precision,
      (item->'configuration'->>'lon')::double precision FROM jsonb_array_elements(p_items) item
      WHERE item->>'kind'='DYNAMIC'
    LOOP
      IF v_lat < -90 OR v_lat > 90 OR v_lon < -180 OR v_lon > 180
      THEN RAISE EXCEPTION 'invalid WEATHER coordinates'; END IF;
    END LOOP;
    INSERT INTO public.player_playlist_drafts(playlist_id,items,updated_at) VALUES(p_playlist_id,p_items,now())
    ON CONFLICT(playlist_id) DO UPDATE SET items=EXCLUDED.items,updated_at=EXCLUDED.updated_at RETURNING * INTO v_result;
    RETURN v_result;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN RAISE EXCEPTION 'invalid draft field';
END; $$;

CREATE OR REPLACE FUNCTION public.publish_player_playlist_draft(p_playlist_id UUID)
RETURNS public.player_playlist_versions
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE v_owner UUID := auth.uid(); v_items JSONB; v_result public.player_playlist_versions%ROWTYPE;
BEGIN
    IF v_owner IS NULL THEN RAISE EXCEPTION 'authentication required'; END IF;
    SELECT d.items INTO v_items FROM public.player_playlist_drafts d JOIN public.player_playlists p ON p.id=d.playlist_id
      WHERE d.playlist_id=p_playlist_id AND p.owner_id=v_owner;
    IF v_items IS NULL OR jsonb_array_length(v_items)=0 THEN RAISE EXCEPTION 'draft items required'; END IF;
    SELECT * INTO v_result FROM public.publish_player_playlist_version(p_playlist_id,v_items);
    RETURN v_result;
END; $$;

REVOKE ALL ON FUNCTION public.register_player_media_asset(UUID,TEXT,TEXT,BIGINT,TEXT,TEXT) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.save_player_playlist_draft(UUID,JSONB) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.publish_player_playlist_draft(UUID) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.register_player_media_asset(UUID,TEXT,TEXT,BIGINT,TEXT,TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.save_player_playlist_draft(UUID,JSONB) TO authenticated;
GRANT EXECUTE ON FUNCTION public.publish_player_playlist_draft(UUID) TO authenticated;
