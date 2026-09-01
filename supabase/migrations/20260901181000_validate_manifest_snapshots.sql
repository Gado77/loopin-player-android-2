CREATE OR REPLACE FUNCTION public.validate_player_manifest_snapshot()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = public AS $$
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
        WHERE NOT item ?& ARRAY['id','order','kind']
           OR length(trim(item->>'id')) = 0
           OR (item->>'order')::integer < 0
           OR item->>'kind' NOT IN ('MEDIA','DYNAMIC')
           OR (item->>'kind' = 'MEDIA' AND (
                NOT item ?& ARRAY['mediaType','assetId','expectedSizeBytes','sha256','mimeType']
                OR item->>'mediaType' NOT IN ('VIDEO','IMAGE')
                OR (item->>'expectedSizeBytes')::bigint < 0
                OR item->>'sha256' !~ '^[a-f0-9]{64}$'
                OR (item->>'mediaType' = 'IMAGE' AND COALESCE((item->>'durationMs')::bigint, 0) <= 0)
                OR EXISTS (SELECT 1 FROM jsonb_object_keys(item) k WHERE k NOT IN ('id','order','kind','mediaType','assetId','durationMs','expectedSizeBytes','sha256','mimeType','remoteUrl'))
           ))
           OR (item->>'kind' = 'DYNAMIC' AND (
                NOT item ?& ARRAY['dynamicType','durationMs','configuration']
                OR item->>'dynamicType' <> 'WEATHER'
                OR (item->>'durationMs')::bigint <= 0
                OR jsonb_typeof(item->'configuration') <> 'object'
                OR NOT (item->'configuration') ?& ARRAY['city','lat','lon']
                OR EXISTS (SELECT 1 FROM jsonb_object_keys(item->'configuration') k WHERE k NOT IN ('city','lat','lon'))
                OR EXISTS (SELECT 1 FROM jsonb_object_keys(item) k WHERE k NOT IN ('id','order','kind','dynamicType','durationMs','configuration'))
           ))
    ) THEN RAISE EXCEPTION 'invalid manifest snapshot item'; END IF;

    IF (SELECT count(*) FROM jsonb_array_elements(NEW.manifest->'items')) <>
       (SELECT count(DISTINCT item->>'id') FROM jsonb_array_elements(NEW.manifest->'items') item)
       OR (SELECT count(*) FROM jsonb_array_elements(NEW.manifest->'items')) <>
       (SELECT count(DISTINCT item->>'order') FROM jsonb_array_elements(NEW.manifest->'items') item)
    THEN RAISE EXCEPTION 'duplicate manifest item id or order'; END IF;
    RETURN NEW;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN
    RAISE EXCEPTION 'invalid manifest numeric field';
END;
$$;

DROP TRIGGER IF EXISTS validate_player_manifest_snapshot_insert ON public.player_playlist_versions;
CREATE TRIGGER validate_player_manifest_snapshot_insert
BEFORE INSERT ON public.player_playlist_versions
FOR EACH ROW EXECUTE FUNCTION public.validate_player_manifest_snapshot();

REVOKE ALL ON FUNCTION public.validate_player_manifest_snapshot() FROM PUBLIC, anon, authenticated;
