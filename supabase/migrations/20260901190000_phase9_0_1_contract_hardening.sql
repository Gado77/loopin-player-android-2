CREATE OR REPLACE FUNCTION public.publish_player_playlist_version(p_playlist_id UUID, p_items JSONB)
RETURNS public.player_playlist_versions
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions
AS $$
DECLARE
    v_owner UUID := auth.uid();
    v_version BIGINT;
    v_normalized_items JSONB;
    v_manifest JSONB;
    v_result public.player_playlist_versions%ROWTYPE;
BEGIN
    IF v_owner IS NULL THEN RAISE EXCEPTION 'authentication required'; END IF;
    PERFORM 1 FROM public.player_playlists WHERE id = p_playlist_id AND owner_id = v_owner FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'playlist unavailable'; END IF;
    IF jsonb_typeof(p_items) <> 'array' OR jsonb_array_length(p_items) = 0 THEN RAISE EXCEPTION 'items required'; END IF;

    IF EXISTS (
        SELECT 1 FROM jsonb_array_elements(p_items) item
        WHERE NOT item ?& ARRAY['id','order','kind']
           OR length(trim(COALESCE(item->>'id', ''))) = 0
           OR COALESCE((item->>'order')::integer, -1) < 0
           OR item->>'kind' NOT IN ('MEDIA','DYNAMIC')
           OR (item->>'kind' = 'MEDIA' AND (
                NOT item ? 'assetId'
                OR EXISTS (SELECT 1 FROM jsonb_object_keys(item) k WHERE k NOT IN ('id','order','kind','assetId','durationMs'))
           ))
           OR (item->>'kind' = 'DYNAMIC' AND (
                NOT item ?& ARRAY['dynamicType','durationMs','configuration']
                OR item->>'dynamicType' <> 'WEATHER'
                OR EXISTS (SELECT 1 FROM jsonb_object_keys(item) k WHERE k NOT IN ('id','order','kind','dynamicType','durationMs','configuration'))
           ))
    ) THEN RAISE EXCEPTION 'invalid publication item'; END IF;

    IF (SELECT count(*) FROM jsonb_array_elements(p_items)) <> (SELECT count(DISTINCT item->>'id') FROM jsonb_array_elements(p_items) item)
       OR (SELECT count(*) FROM jsonb_array_elements(p_items)) <> (SELECT count(DISTINCT item->>'order') FROM jsonb_array_elements(p_items) item)
    THEN RAISE EXCEPTION 'duplicate item id or order'; END IF;

    IF EXISTS (
        SELECT 1 FROM jsonb_array_elements(p_items) item
        LEFT JOIN public.player_media_assets asset
          ON item->>'kind' = 'MEDIA' AND asset.id = (item->>'assetId')::uuid AND asset.owner_id = v_owner
        WHERE item->>'kind' = 'MEDIA' AND asset.id IS NULL
    ) THEN RAISE EXCEPTION 'media asset unavailable'; END IF;

    SELECT jsonb_agg(
        CASE WHEN item->>'kind' = 'MEDIA' THEN
            jsonb_strip_nulls(jsonb_build_object(
                'id', item->>'id', 'order', (item->>'order')::integer, 'kind', 'MEDIA',
                'mediaType', asset.media_type, 'assetId', asset.id::text,
                'durationMs', item->'durationMs', 'expectedSizeBytes', asset.expected_size_bytes,
                'sha256', asset.sha256, 'mimeType', asset.mime_type
            ))
        ELSE jsonb_build_object(
            'id', item->>'id', 'order', (item->>'order')::integer, 'kind', 'DYNAMIC',
            'dynamicType', 'WEATHER', 'durationMs', (item->>'durationMs')::bigint,
            'configuration', item->'configuration'
        ) END ORDER BY (item->>'order')::integer
    ) INTO v_normalized_items
    FROM jsonb_array_elements(p_items) item
    LEFT JOIN public.player_media_assets asset
      ON item->>'kind' = 'MEDIA' AND asset.id = (item->>'assetId')::uuid AND asset.owner_id = v_owner;

    SELECT COALESCE(max(version_number), 0) + 1 INTO v_version FROM public.player_playlist_versions WHERE playlist_id = p_playlist_id;
    v_manifest := jsonb_build_object(
        'schemaVersion', 2, 'playlistId', p_playlist_id::text, 'playlistVersion', v_version,
        'generatedAtEpochMs', floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint,
        'items', v_normalized_items
    );
    INSERT INTO public.player_playlist_versions(playlist_id, version_number, manifest, manifest_sha256)
    VALUES (p_playlist_id, v_version, v_manifest, encode(digest(v_manifest::text, 'sha256'), 'hex')) RETURNING * INTO v_result;
    RETURN v_result;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN
    RAISE EXCEPTION 'invalid publication field';
END;
$$;

CREATE OR REPLACE FUNCTION public.validate_player_manifest_snapshot()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = public AS $$
DECLARE v_lat DOUBLE PRECISION; v_lon DOUBLE PRECISION;
BEGIN
    IF jsonb_typeof(NEW.manifest) <> 'object'
       OR NOT NEW.manifest ?& ARRAY['schemaVersion','playlistId','playlistVersion','generatedAtEpochMs','items']
       OR EXISTS (SELECT 1 FROM jsonb_object_keys(NEW.manifest) k WHERE k NOT IN ('schemaVersion','playlistId','playlistVersion','generatedAtEpochMs','items'))
       OR NEW.manifest->>'schemaVersion' <> '2'
       OR NEW.manifest->>'playlistId' <> NEW.playlist_id::text
       OR (NEW.manifest->>'playlistVersion')::bigint <> NEW.version_number
       OR jsonb_typeof(NEW.manifest->'items') <> 'array'
       OR jsonb_array_length(NEW.manifest->'items') = 0
    THEN RAISE EXCEPTION 'invalid manifest snapshot root'; END IF;

    IF EXISTS (
        SELECT 1 FROM jsonb_array_elements(NEW.manifest->'items') item
        WHERE NOT item ?& ARRAY['id','order','kind'] OR length(trim(COALESCE(item->>'id',''))) = 0
           OR COALESCE((item->>'order')::integer, -1) < 0 OR item->>'kind' NOT IN ('MEDIA','DYNAMIC')
           OR (item->>'kind' = 'MEDIA' AND (
                NOT item ?& ARRAY['mediaType','assetId','expectedSizeBytes','sha256','mimeType']
                OR item->>'mediaType' NOT IN ('VIDEO','IMAGE') OR COALESCE((item->>'expectedSizeBytes')::bigint, -1) < 0
                OR COALESCE(item->>'sha256','') !~ '^[a-f0-9]{64}$'
                OR NOT COALESCE(item->>'mimeType','') LIKE CASE WHEN item->>'mediaType' = 'VIDEO' THEN 'video/%' ELSE 'image/%' END
                OR (item->>'mediaType' = 'IMAGE' AND COALESCE((item->>'durationMs')::bigint, 0) <= 0)
                OR EXISTS (SELECT 1 FROM jsonb_object_keys(item) k WHERE k NOT IN ('id','order','kind','mediaType','assetId','durationMs','expectedSizeBytes','sha256','mimeType'))
           ))
           OR (item->>'kind' = 'DYNAMIC' AND (
                NOT item ?& ARRAY['dynamicType','durationMs','configuration'] OR item->>'dynamicType' <> 'WEATHER'
                OR COALESCE((item->>'durationMs')::bigint, 0) <= 0 OR jsonb_typeof(item->'configuration') <> 'object'
                OR NOT (item->'configuration') ?& ARRAY['city','lat','lon']
                OR EXISTS (SELECT 1 FROM jsonb_object_keys(item->'configuration') k WHERE k NOT IN ('city','lat','lon'))
                OR length(trim(COALESCE(item->'configuration'->>'city',''))) = 0
                OR length(trim(COALESCE(item->'configuration'->>'lat',''))) = 0
                OR length(trim(COALESCE(item->'configuration'->>'lon',''))) = 0
                OR EXISTS (SELECT 1 FROM jsonb_object_keys(item) k WHERE k NOT IN ('id','order','kind','dynamicType','durationMs','configuration'))
           ))
    ) THEN RAISE EXCEPTION 'invalid manifest snapshot item'; END IF;

    FOR v_lat, v_lon IN SELECT (item->'configuration'->>'lat')::double precision, (item->'configuration'->>'lon')::double precision
      FROM jsonb_array_elements(NEW.manifest->'items') item WHERE item->>'kind' = 'DYNAMIC'
    LOOP IF v_lat < -90 OR v_lat > 90 OR v_lon < -180 OR v_lon > 180
      THEN RAISE EXCEPTION 'invalid WEATHER coordinates'; END IF; END LOOP;

    IF (SELECT count(*) FROM jsonb_array_elements(NEW.manifest->'items')) <> (SELECT count(DISTINCT item->>'id') FROM jsonb_array_elements(NEW.manifest->'items') item)
       OR (SELECT count(*) FROM jsonb_array_elements(NEW.manifest->'items')) <> (SELECT count(DISTINCT item->>'order') FROM jsonb_array_elements(NEW.manifest->'items') item)
    THEN RAISE EXCEPTION 'duplicate manifest item id or order'; END IF;
    RETURN NEW;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN
    RAISE EXCEPTION 'invalid manifest numeric field';
END;
$$;

REVOKE ALL ON FUNCTION public.publish_player_playlist_version(UUID, JSONB) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.publish_player_playlist_version(UUID, JSONB) TO authenticated;
REVOKE ALL ON FUNCTION public.validate_player_manifest_snapshot() FROM PUBLIC, anon, authenticated;
