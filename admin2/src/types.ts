export type ScreenDevice = {
  id: string;
  pairing_status: "UNPAIRED" | "PAIRED" | "DISABLED";
  last_seen_at: string | null;
  app_version: string | null;
  metadata?: Record<string, unknown> | null;
};

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
