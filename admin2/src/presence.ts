import type { ScreenDevice } from "./types";

export const ONLINE_WINDOW_MS = 12 * 60 * 1000;

export type DevicePresence = "NO_PLAYER" | "AWAITING" | "ONLINE" | "OFFLINE";

export function classifyPresence(
  device: ScreenDevice | undefined,
  nowEpochMs = Date.now(),
): DevicePresence {
  if (!device) return "NO_PLAYER";
  if (device.pairing_status !== "PAIRED" || !device.last_seen_at) return "AWAITING";
  const lastSeen = Date.parse(device.last_seen_at);
  if (!Number.isFinite(lastSeen)) return "AWAITING";
  return nowEpochMs - lastSeen <= ONLINE_WINDOW_MS ? "ONLINE" : "OFFLINE";
}

export function formatLastCommunication(lastSeenAt: string | null | undefined, nowEpochMs = Date.now()): string {
  if (!lastSeenAt) return "ainda não registrada";
  const timestamp = Date.parse(lastSeenAt);
  if (!Number.isFinite(timestamp)) return "ainda não registrada";
  const elapsedMinutes = Math.max(0, Math.floor((nowEpochMs - timestamp) / 60_000));
  if (elapsedMinutes < 1) return "agora";
  if (elapsedMinutes < 60) return `há ${elapsedMinutes} min`;
  const hours = Math.floor(elapsedMinutes / 60);
  if (hours < 24) return `há ${hours} h`;
  const days = Math.floor(hours / 24);
  return `há ${days} ${days === 1 ? "dia" : "dias"}`;
}

export function presenceLabel(presence: DevicePresence): string {
  switch (presence) {
    case "NO_PLAYER": return "SEM PLAYER";
    case "AWAITING": return "AGUARDANDO PLAYER";
    case "ONLINE": return "ONLINE";
    case "OFFLINE": return "OFFLINE";
  }
}
