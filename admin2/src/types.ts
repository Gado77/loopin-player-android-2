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
  screen_playlist_assignments?: ScreenPlaylistAssignment[];
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
