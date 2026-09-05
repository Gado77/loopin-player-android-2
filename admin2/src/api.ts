import type { SupabaseClient } from "@supabase/supabase-js";
import type { DeviceCommand, DeviceHealthEvent, DraftItem, MediaAsset, PairingProof, PairingResult, PlayerCommandType, Playlist, PlaylistVersion, Screen } from "./types";
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
    .select("id, owner_id, name, status, created_at, devices(id, pairing_status, last_seen_at, app_version, metadata, runtime_status:device_runtime_status(device_id,screen_id,last_seen_at,session_id,app_version,uptime_ms,available_memory_bytes,memory_low,free_storage_bytes,total_storage_bytes,playback_state,cache_state,sync_state,health_state,last_sync_at,last_error_code,last_error_summary,last_error_at,active_playlist_id,active_playlist_version,active_manifest_etag,previous_playlist_id,current_item_id,current_content_kind,current_media_type,updated_at)), playlist_assignment:screen_playlist_assignments(playlist_version_id, assigned_at, player_playlist_versions(id, playlist_id, version_number, manifest_sha256, published_at, player_playlists(id, name)))")
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
export async function enqueuePlayerCommand(client:SupabaseClient,screenId:string,commandType:PlayerCommandType):Promise<DeviceCommand>{const{data,error}=await client.rpc("enqueue_player_command",{p_screen_id:screenId,p_command_type:commandType,p_payload:null});if(error||!data)throw new Error("Não foi possível enviar o comando.");return data as DeviceCommand;}

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
