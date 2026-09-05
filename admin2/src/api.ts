import type { SupabaseClient } from "@supabase/supabase-js";
import type { DeviceCommand, DeviceHealthEvent, DeviceUpdateAttempt, DraftItem, MediaAsset, PairingProof, PairingResult, PlayerCommandType, PlayerRelease, Playlist, PlaylistVersion, Screen, ScreenGroup, UpdateRollout } from "./types";
import { hashFile, safeExtension, validateMediaFile } from "./media";
import { pairingErrorMessage } from "./pairing";

export async function login(client: SupabaseClient, email: string, password: string) {
  const { data, error } = await client.auth.signInWithPassword({ email, password });
  if (error) throw new Error("Email ou senha inválidos.");
  return data.session;
}

export async function logout(client: SupabaseClient) {
  const { error } = await client.auth.signOut();
  if (error) throw new Error("Não foi possível sair agora.");
}

export async function listScreens(client: SupabaseClient): Promise<Screen[]> {
  const { data, error } = await client
    .from("screens")
    .select("id, owner_id, name, status, created_at, devices(id, pairing_status, last_seen_at, app_version, metadata, update_channel, runtime_status:device_runtime_status(device_id,screen_id,last_seen_at,session_id,app_version,uptime_ms,available_memory_bytes,memory_low,free_storage_bytes,total_storage_bytes,playback_state,cache_state,sync_state,health_state,last_sync_at,last_error_code,last_error_summary,last_error_at,active_playlist_id,active_playlist_version,active_manifest_etag,previous_playlist_id,current_item_id,current_content_kind,current_media_type,current_version_code,update_state,available_version_code,prepared_version_code,last_update_check,last_update_error,installation_capability,installation_state,install_requested_at,post_update_verification_state,last_install_failure_code,updated_at)), playlist_assignment:screen_playlist_assignments(playlist_version_id, assigned_at, player_playlist_versions(id, playlist_id, version_number, manifest_sha256, published_at, player_playlists(id, name)))")
    .order("created_at", { ascending: false });
  if (error) throw new Error("Não foi possível carregar suas telas.");
  return (data ?? []) as unknown as Screen[];
}

export async function listPublishedPlaylistVersions(client: SupabaseClient): Promise<PlaylistVersion[]> {
  const { data, error } = await client.from("player_playlist_versions")
    .select("id, playlist_id, version_number, manifest_sha256, published_at, player_playlists(id, name)")
    .order("published_at", { ascending: false });
  if (error) throw new Error("Não foi possível carregar as playlists publicadas.");
  return (data ?? []) as unknown as PlaylistVersion[];
}

export async function assignPlaylistVersion(client: SupabaseClient, screenId: string, versionId: string) {
  const { data, error } = await client.rpc("assign_player_playlist_version", {
    p_screen_id: screenId, p_playlist_version_id: versionId,
  });
  if (error) throw new Error("Não foi possível alterar a playlist desta tela.");
  return data;
}

export async function createScreen(client: SupabaseClient, ownerId: string, name: string): Promise<Screen> {
  const normalized = name.trim();
  if (!normalized || normalized.length > 100) throw new Error("Informe um nome de tela válido.");
  const { data, error } = await client
    .from("screens")
    .insert({ owner_id: ownerId, name: normalized, status: "ACTIVE" })
    .select("id, owner_id, name, status, created_at")
    .single();
  if (error || !data) throw new Error("Não foi possível criar a tela.");
  return { ...data, devices: [] } as Screen;
}

export async function listMediaAssets(client:SupabaseClient):Promise<MediaAsset[]>{const {data,error}=await client.from("player_media_assets").select("id,name,media_type,expected_size_bytes,sha256,mime_type,storage_path,created_at").order("created_at",{ascending:false});if(error)throw new Error("Não foi possível carregar suas mídias.");return (data??[]) as MediaAsset[];}
export async function uploadMediaAsset(client:SupabaseClient,userId:string,file:File,onState:(state:string)=>void=()=>{}):Promise<{asset:MediaAsset;deduplicated:boolean}>{const mediaType=validateMediaFile(file);onState("Calculando integridade…");const sha256=await hashFile(file);const existing=(await listMediaAssets(client)).find(asset=>asset.sha256===sha256);if(existing)return{asset:existing,deduplicated:true};const assetId=crypto.randomUUID();const path=`users/${userId}/${assetId}/original.${safeExtension(file.type)}`;onState("Enviando…");const upload=await client.storage.from("player2-media").upload(path,file,{contentType:file.type,upsert:false});if(upload.error)throw new Error("Falha ao enviar a mídia.");onState("Registrando…");const {data,error}=await client.rpc("register_player_media_asset",{p_asset_id:assetId,p_name:file.name,p_media_type:mediaType,p_expected_size_bytes:file.size,p_sha256:sha256,p_mime_type:file.type});if(error)throw new Error("Mídia enviada, mas o registro seguro falhou.");onState("Concluído.");return{asset:data as MediaAsset,deduplicated:false};}
export async function listPlaylists(client:SupabaseClient):Promise<Playlist[]>{const {data,error}=await client.from("player_playlists").select("id,name,created_at,player_playlist_drafts(items,updated_at),player_playlist_versions(id,playlist_id,version_number,manifest_sha256,published_at)").order("created_at",{ascending:false});if(error)throw new Error("Não foi possível carregar suas playlists.");return(data??[]) as unknown as Playlist[];}
export async function createPlaylist(client:SupabaseClient,ownerId:string,name:string):Promise<Playlist>{const normalized=name.trim();if(!normalized||normalized.length>100)throw new Error("Informe um nome válido.");const {data,error}=await client.from("player_playlists").insert({owner_id:ownerId,name:normalized}).select("id,name,created_at").single();if(error||!data)throw new Error("Não foi possível criar a playlist.");return data as Playlist;}
export async function savePlaylistDraft(client:SupabaseClient,playlistId:string,items:DraftItem[]){const {data,error}=await client.rpc("save_player_playlist_draft",{p_playlist_id:playlistId,p_items:items});if(error)throw new Error("Não foi possível salvar o rascunho.");return data;}
export async function publishPlaylistDraft(client:SupabaseClient,playlistId:string):Promise<PlaylistVersion>{const {data,error}=await client.rpc("publish_player_playlist_draft",{p_playlist_id:playlistId});if(error)throw new Error("Não foi possível publicar o rascunho.");return data as PlaylistVersion;}
export async function listRecentCommands(client:SupabaseClient):Promise<DeviceCommand[]>{const{data,error}=await client.from("device_commands").select("id,screen_id,device_id,command_type,status,created_at,delivered_at,completed_at,expires_at,result").order("created_at",{ascending:false}).limit(50);if(error)throw new Error("Não foi possível carregar os comandos.");return(data??[]) as DeviceCommand[];}
export async function listHealthEvents(client:SupabaseClient):Promise<DeviceHealthEvent[]>{const{data,error}=await client.from("device_health_events").select("id,device_id,screen_id,event_type,severity,occurred_at,metadata").order("occurred_at",{ascending:false}).limit(50);if(error)throw new Error("Não foi possível carregar o histórico de diagnóstico.");return(data??[]) as DeviceHealthEvent[];}
export async function listUpdateAttempts(client:SupabaseClient):Promise<DeviceUpdateAttempt[]>{const{data,error}=await client.from("device_update_attempts").select("id,device_id,screen_id,release_id,from_version_code,target_version_code,state,requested_at,install_started_at,first_seen_target_at,completed_at,failure_code").order("requested_at",{ascending:false}).limit(50);if(error)throw new Error("Não foi possível carregar o histórico OTA.");return(data??[]) as DeviceUpdateAttempt[];}
export async function listScreenGroups(client:SupabaseClient):Promise<ScreenGroup[]>{const{data,error}=await client.from("screen_groups").select("id,owner_id,name,description,created_at,updated_at,members:screen_group_members(screen_id)").order("name");if(error)throw new Error("Não foi possível carregar os grupos.");return(data??[]) as unknown as ScreenGroup[];}
export async function saveScreenGroup(client:SupabaseClient,id:string|null,name:string,description:string,screenIds:string[]){const{data,error}=await client.rpc("set_screen_group",{p_group_id:id,p_name:name,p_description:description,p_screen_ids:screenIds});if(error)throw new Error("Não foi possível salvar o grupo.");return data as ScreenGroup;}
export async function listUpdateRollouts(client:SupabaseClient):Promise<UpdateRollout[]>{const{data,error}=await client.from("update_rollouts").select("id,owner_id,release_id,name,status,waves,current_wave,scheduled_start_at,maintenance_timezone,maintenance_start_local,maintenance_end_local,failure_threshold_percent,created_at,started_at,paused_at,completed_at,canceled_at,release:player_releases(*),devices:update_rollout_devices(*),group_targets:update_rollout_group_targets(group_id),screen_targets:update_rollout_screen_targets(screen_id)").order("created_at",{ascending:false});if(error)throw new Error("Não foi possível carregar os rollouts.");return(data??[]) as unknown as UpdateRollout[];}
export async function createUpdateRollout(client:SupabaseClient,input:{releaseId:string;name:string;groupIds:string[];screenIds:string[];waves:number[];scheduledStartAt:string|null;timezone:string|null;windowStart:string|null;windowEnd:string|null}){const{data,error}=await client.rpc("create_update_rollout",{p_release_id:input.releaseId,p_name:input.name,p_group_ids:input.groupIds,p_screen_ids:input.screenIds,p_waves:input.waves,p_scheduled_start_at:input.scheduledStartAt,p_timezone:input.timezone,p_window_start:input.windowStart,p_window_end:input.windowEnd});if(error)throw new Error("Não foi possível criar o rollout.");return data as UpdateRollout;}
export async function activateUpdateRollout(client:SupabaseClient,id:string){const{data,error}=await client.rpc("activate_update_rollout",{p_rollout_id:id});if(error)throw new Error("Não foi possível ativar: verifique targets, canal e conflitos.");return data as UpdateRollout;}
export async function controlUpdateRollout(client:SupabaseClient,id:string,action:"PAUSE"|"RESUME"|"ADVANCE"|"CANCEL"){const{data,error}=await client.rpc("control_update_rollout",{p_rollout_id:id,p_action:action});if(error)throw new Error("Transição de rollout não permitida.");return data as UpdateRollout;}
export async function enqueuePlayerCommand(client:SupabaseClient,screenId:string,commandType:PlayerCommandType):Promise<DeviceCommand>{const{data,error}=await client.rpc("enqueue_player_command",{p_screen_id:screenId,p_command_type:commandType,p_payload:null});if(error||!data)throw new Error("Não foi possível enviar o comando.");return data as DeviceCommand;}
export async function isReleaseAdmin(client:SupabaseClient){const {data,error}=await client.from("release_admins").select("user_id").maybeSingle();if(error)throw new Error("Não foi possível verificar a permissão de releases.");return !!data;}
export async function listPlayerReleases(client:SupabaseClient):Promise<PlayerRelease[]>{const {data,error}=await client.from("player_releases").select("id,channel,version_code,version_name,package_name,apk_size_bytes,apk_sha256,certificate_sha256,status,release_notes,created_at,published_at,inspected_at").order("created_at",{ascending:false});if(error)throw new Error("Não foi possível carregar releases.");return(data??[]) as PlayerRelease[];}
export async function createReleaseUpload(client:SupabaseClient,file:File,channel:"STABLE"|"BETA",notes:string,onProgress:(s:string)=>void){if(!file.name.toLowerCase().endsWith(".apk")||file.size<1||file.size>100*1024*1024)throw new Error("Selecione um APK válido de até 100 MiB.");onProgress("Criando release DRAFT…");const {data,error}=await client.functions.invoke("admin-releases",{body:{action:"create",channel,size:file.size,release_notes:notes}});if(error||!data?.upload?.token)throw new Error("Não foi possível criar o release.");onProgress("Enviando APK privado…");const upload=await client.storage.from("player2-releases").uploadToSignedUrl(data.upload.path,data.upload.token,file,{contentType:"application/vnd.android.package-archive"});if(upload.error)throw new Error("Falha no upload do APK.");onProgress("Upload concluído; aguardando inspeção autoritativa.");return data.release as PlayerRelease;}
export async function publishRelease(client:SupabaseClient,id:string,revoke=false){const {data,error}=await client.rpc("publish_player_release",{p_release_id:id,p_revoke:revoke});if(error)throw new Error(revoke?"Não foi possível revogar o release.":"O release precisa ser inspecionado antes da publicação.");return data as PlayerRelease;}
export async function setDeviceUpdateChannel(client:SupabaseClient,screenId:string,channel:"STABLE"|"BETA"){const {error}=await client.rpc("set_device_update_channel",{p_screen_id:screenId,p_channel:channel});if(error)throw new Error("Não foi possível alterar o canal.");}

export async function pairPlayer(
  client: SupabaseClient,
  screenId: string,
  proof: PairingProof,
): Promise<PairingResult> {
  const payload = proof.kind === "code"
    ? { action: "confirm", screen_id: screenId, pairing_code: proof.code }
    : { action: "confirm", screen_id: screenId, pairing_token: proof.token };
  const { data, error } = await client.functions.invoke("device-pairing", { body: payload });
  if (error) {
    let code = "network_error";
    const context = (error as { context?: Response }).context;
    if (context) {
      try {
        const response = await context.clone().json() as { error?: string };
        code = response.error ?? code;
      } catch { /* resposta sem JSON */ }
    }
    throw new Error(pairingErrorMessage(code));
  }
  return data as PairingResult;
}
