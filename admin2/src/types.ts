export type Screen = {
  id: string;
  owner_id: string;
  name: string;
  status: "ACTIVE" | "DISABLED";
  created_at: string;
  devices?: Array<{
    id: string;
    pairing_status: "UNPAIRED" | "PAIRED" | "DISABLED";
  }>;
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
