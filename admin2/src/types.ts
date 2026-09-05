export type ScreenDevice = {
  id: string;
  pairing_status: "UNPAIRED" | "PAIRED" | "DISABLED";
  last_seen_at: string | null;
  app_version: string | null;
  metadata?: Record<string, unknown> | null;
  runtime_status?: DeviceRuntimeStatus | null;
  update_channel?: "STABLE"|"BETA";
};

export type DeviceRuntimeStatus={device_id:string;screen_id:string;last_seen_at:string;session_id:string;app_version:string;uptime_ms:number;available_memory_bytes:number;memory_low:boolean;free_storage_bytes:number;total_storage_bytes:number;playback_state:"PLAYING"|"PAUSED"|"ERROR"|"OFFLINE"|"IDLE";cache_state:"OK"|"INCOMPLETE"|"ERROR";sync_state:"OK"|"SYNCING"|"ERROR"|"NEVER_SYNCED";health_state:"HEALTHY"|"DEGRADED"|"ERROR";last_sync_at:string|null;last_error_code:string|null;last_error_summary:string|null;last_error_at:string|null;active_playlist_id:string|null;active_playlist_version:number|null;active_manifest_etag:string|null;previous_playlist_id:string|null;current_item_id:string|null;current_content_kind:"MEDIA"|"DYNAMIC"|null;current_media_type:"VIDEO"|"IMAGE"|"WEATHER"|null;update_channel?:"STABLE"|"BETA"|null;current_version_code?:number|null;update_state?:string|null;available_version_code?:number|null;prepared_version_code?:number|null;last_update_check?:string|null;last_update_error?:string|null;installation_capability?:string|null;installation_state?:string|null;install_requested_at?:string|null;post_update_verification_state?:string|null;last_install_failure_code?:string|null;updated_at:string};
export type DeviceHealthEvent={id:string;device_id:string;screen_id:string;event_type:string;severity:"INFO"|"WARNING"|"ERROR";occurred_at:string;metadata:Record<string,unknown>};

export type Screen = {
  id: string;
  owner_id: string;
  name: string;
  status: "ACTIVE" | "DISABLED";
  created_at: string;
  devices?: ScreenDevice[];
  playlist_assignment?: ScreenPlaylistAssignment | null;
};

export type PlaylistVersion = {
  id: string;
  playlist_id: string;
  version_number: number;
  manifest_sha256: string;
  published_at: string;
  player_playlists?: { id: string; name: string } | null;
};

export type ScreenPlaylistAssignment = {
  playlist_version_id: string;
  assigned_at: string;
  player_playlist_versions?: PlaylistVersion | null;
};

export type PairingProof =
  | { kind: "code"; code: string }
  | { kind: "token"; token: string };

export type PairingResult = {
  state: "PAIRED";
  device_id: string;
  screen_id: string;
  screen_name: string;
  paired_at: string;
};

export type MediaAsset = { id:string; name:string; media_type:"VIDEO"|"IMAGE"; expected_size_bytes:number; sha256:string; mime_type:string; storage_path:string; created_at:string };
export type DraftMediaItem = { id:string; order:number; kind:"MEDIA"; assetId:string; durationMs?:number };
export type DraftWeatherItem = { id:string; order:number; kind:"DYNAMIC"; dynamicType:"WEATHER"; durationMs:number; configuration:{city:string;lat:string;lon:string} };
export type DraftItem = DraftMediaItem | DraftWeatherItem;
export type Playlist = { id:string; name:string; created_at:string; player_playlist_drafts?:{items:DraftItem[];updated_at:string}|null; player_playlist_versions?:PlaylistVersion[] };
export type PlayerCommandType = "GET_STATUS"|"SYNC_NOW"|"RELOAD_PLAYLIST"|"CHECK_UPDATE"|"INSTALL_UPDATE";
export type DeviceUpdateAttempt={id:string;device_id:string;screen_id:string;release_id:string;from_version_code:number;target_version_code:number;state:string;requested_at:string;install_started_at:string|null;first_seen_target_at:string|null;completed_at:string|null;failure_code:string|null};
export type ScreenGroup={id:string;owner_id:string;name:string;description:string|null;created_at:string;updated_at:string;members?:{screen_id:string}[]};
export type RolloutStatus="DRAFT"|"SCHEDULED"|"ACTIVE"|"PAUSED"|"PAUSED_AUTO"|"COMPLETED"|"CANCELED";
export type RolloutDevice={id:string;rollout_id:string;screen_id:string;device_id:string|null;cohort_score:number|null;state:string;first_check_at:string|null;ready_at:string|null;installed_at:string|null;failure_code:string|null};
export type UpdateRollout={id:string;owner_id:string;release_id:string;name:string;status:RolloutStatus;waves:number[];current_wave:number;scheduled_start_at:string|null;maintenance_timezone:string|null;maintenance_start_local:string|null;maintenance_end_local:string|null;failure_threshold_percent:number;created_at:string;started_at:string|null;paused_at:string|null;completed_at:string|null;canceled_at:string|null;release?:PlayerRelease|null;devices?:RolloutDevice[];group_targets?:{group_id:string}[];screen_targets?:{screen_id:string}[]};
export type PlayerRelease={id:string;channel:"STABLE"|"BETA";version_code:number|null;version_name:string|null;package_name:string|null;apk_size_bytes:number;apk_sha256:string|null;certificate_sha256:string|null;status:"DRAFT"|"PUBLISHED"|"REVOKED";release_notes:string|null;created_at:string;published_at:string|null;inspected_at:string|null};
export type PlayerCommandStatus = "PENDING"|"DELIVERED"|"SUCCEEDED"|"FAILED"|"EXPIRED";
export type DeviceCommand = { id:string; screen_id:string; device_id:string; command_type:PlayerCommandType; status:PlayerCommandStatus; created_at:string; delivered_at:string|null; completed_at:string|null; expires_at:string; result:Record<string,unknown>|null };
