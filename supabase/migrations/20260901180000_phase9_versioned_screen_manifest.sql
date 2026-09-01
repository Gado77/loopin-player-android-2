CREATE TABLE public.player_playlists (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE public.player_media_assets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 200),
    media_type TEXT NOT NULL CHECK (media_type IN ('VIDEO', 'IMAGE')),
    expected_size_bytes BIGINT NOT NULL CHECK (expected_size_bytes >= 0),
    sha256 TEXT NOT NULL CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    mime_type TEXT NOT NULL CHECK (length(mime_type) BETWEEN 1 AND 100),
    storage_path TEXT NOT NULL CHECK (length(storage_path) BETWEEN 1 AND 500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(owner_id, sha256)
);

CREATE TABLE public.player_playlist_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    playlist_id UUID NOT NULL REFERENCES public.player_playlists(id) ON DELETE RESTRICT,
    version_number BIGINT NOT NULL CHECK (version_number > 0),
    manifest JSONB NOT NULL,
    manifest_sha256 TEXT NOT NULL CHECK (manifest_sha256 ~ '^[a-f0-9]{64}$'),
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(playlist_id, version_number),
    UNIQUE(playlist_id, manifest_sha256)
);

CREATE TABLE public.screen_playlist_assignments (
    screen_id UUID PRIMARY KEY REFERENCES public.screens(id) ON DELETE CASCADE,
    playlist_version_id UUID NOT NULL REFERENCES public.player_playlist_versions(id) ON DELETE RESTRICT,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by UUID NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT
);

ALTER TABLE public.player_playlists ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.player_media_assets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.player_playlist_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.screen_playlist_assignments ENABLE ROW LEVEL SECURITY;

CREATE POLICY player_playlists_select_own ON public.player_playlists FOR SELECT TO authenticated USING (owner_id = auth.uid());
CREATE POLICY player_playlists_insert_own ON public.player_playlists FOR INSERT TO authenticated WITH CHECK (owner_id = auth.uid());
CREATE POLICY player_media_assets_select_own ON public.player_media_assets FOR SELECT TO authenticated USING (owner_id = auth.uid());
CREATE POLICY playlist_versions_select_own ON public.player_playlist_versions FOR SELECT TO authenticated USING (
    EXISTS (SELECT 1 FROM public.player_playlists p WHERE p.id = playlist_id AND p.owner_id = auth.uid())
);
CREATE POLICY assignments_select_own ON public.screen_playlist_assignments FOR SELECT TO authenticated USING (
    EXISTS (SELECT 1 FROM public.screens s WHERE s.id = screen_id AND s.owner_id = auth.uid())
);

REVOKE UPDATE, DELETE ON public.player_playlist_versions FROM anon, authenticated;
REVOKE INSERT, UPDATE, DELETE ON public.screen_playlist_assignments FROM anon, authenticated;
REVOKE ALL ON public.player_playlists, public.player_media_assets, public.player_playlist_versions,
    public.screen_playlist_assignments FROM anon;
GRANT SELECT, INSERT ON public.player_playlists TO authenticated;
GRANT SELECT ON public.player_media_assets, public.player_playlist_versions, public.screen_playlist_assignments TO authenticated;

CREATE OR REPLACE FUNCTION public.publish_player_playlist_version(p_playlist_id UUID, p_items JSONB)
RETURNS public.player_playlist_versions
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_version BIGINT;
    v_manifest JSONB;
    v_result public.player_playlist_versions%ROWTYPE;
BEGIN
    IF v_owner IS NULL THEN RAISE EXCEPTION 'authentication required'; END IF;
    PERFORM 1 FROM public.player_playlists WHERE id = p_playlist_id AND owner_id = v_owner FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'playlist unavailable'; END IF;
    IF jsonb_typeof(p_items) <> 'array' OR jsonb_array_length(p_items) = 0 THEN RAISE EXCEPTION 'items required'; END IF;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_items) i WHERE
        (i->>'kind' NOT IN ('MEDIA', 'DYNAMIC')) OR
        (i->>'kind' = 'DYNAMIC' AND (i->>'dynamicType' <> 'WEATHER' OR i ?| ARRAY['assetId','sha256','expectedSizeBytes','mimeType','mediaType','remoteUrl'])) OR
        (i->>'kind' = 'MEDIA' AND (NOT i ?& ARRAY['assetId','sha256','expectedSizeBytes','mimeType','mediaType'] OR i ?| ARRAY['dynamicType','configuration']))
    ) THEN RAISE EXCEPTION 'invalid manifest item'; END IF;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_items) i LEFT JOIN public.player_media_assets a
        ON a.id = (i->>'assetId')::uuid AND a.owner_id = v_owner
        WHERE i->>'kind' = 'MEDIA' AND a.id IS NULL) THEN RAISE EXCEPTION 'media asset unavailable'; END IF;
    IF (SELECT count(*) FROM jsonb_array_elements(p_items)) <> (SELECT count(DISTINCT i->>'id') FROM jsonb_array_elements(p_items) i)
       OR (SELECT count(*) FROM jsonb_array_elements(p_items)) <> (SELECT count(DISTINCT i->>'order') FROM jsonb_array_elements(p_items) i)
    THEN RAISE EXCEPTION 'duplicate item id or order'; END IF;

    SELECT COALESCE(max(version_number), 0) + 1 INTO v_version FROM public.player_playlist_versions WHERE playlist_id = p_playlist_id;
    v_manifest := jsonb_build_object(
        'schemaVersion', 2, 'playlistId', p_playlist_id::text, 'playlistVersion', v_version,
        'generatedAtEpochMs', floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint,
        'items', (SELECT jsonb_agg(i ORDER BY (i->>'order')::integer) FROM jsonb_array_elements(p_items) i)
    );
    INSERT INTO public.player_playlist_versions(playlist_id, version_number, manifest, manifest_sha256)
    VALUES (p_playlist_id, v_version, v_manifest, encode(digest(v_manifest::text, 'sha256'), 'hex')) RETURNING * INTO v_result;
    RETURN v_result;
END;
$$;

CREATE OR REPLACE FUNCTION public.assign_player_playlist_version(p_screen_id UUID, p_playlist_version_id UUID)
RETURNS public.screen_playlist_assignments
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE v_owner UUID := auth.uid(); v_result public.screen_playlist_assignments%ROWTYPE;
BEGIN
    IF v_owner IS NULL THEN RAISE EXCEPTION 'authentication required'; END IF;
    PERFORM 1 FROM public.screens s JOIN public.player_playlist_versions v ON v.id = p_playlist_version_id
      JOIN public.player_playlists p ON p.id = v.playlist_id
      WHERE s.id = p_screen_id AND s.owner_id = v_owner AND p.owner_id = v_owner AND s.status = 'ACTIVE';
    IF NOT FOUND THEN RAISE EXCEPTION 'screen or playlist version unavailable'; END IF;
    INSERT INTO public.screen_playlist_assignments(screen_id, playlist_version_id, assigned_by)
    VALUES (p_screen_id, p_playlist_version_id, v_owner)
    ON CONFLICT (screen_id) DO UPDATE SET playlist_version_id = EXCLUDED.playlist_version_id,
      assigned_at = now(), assigned_by = EXCLUDED.assigned_by RETURNING * INTO v_result;
    RETURN v_result;
END;
$$;

REVOKE ALL ON FUNCTION public.publish_player_playlist_version(UUID, JSONB) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.assign_player_playlist_version(UUID, UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.publish_player_playlist_version(UUID, JSONB) TO authenticated;
GRANT EXECUTE ON FUNCTION public.assign_player_playlist_version(UUID, UUID) TO authenticated;
