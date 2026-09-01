import { describe, expect, it } from "vitest";
import { classifyPresence, formatLastCommunication, ONLINE_WINDOW_MS } from "./presence";
import type { ScreenDevice } from "./types";

const now = Date.parse("2026-09-01T12:00:00Z");
const device = (lastSeenAt: string | null): ScreenDevice => ({
  id: "d1",
  pairing_status: "PAIRED",
  last_seen_at: lastSeenAt,
  app_version: "2.0.0-phase8.3-presence",
});

describe("device presence", () => {
  it("classifica tela sem Player", () => expect(classifyPresence(undefined, now)).toBe("NO_PLAYER"));
  it("classifica Player pareado sem heartbeat", () => expect(classifyPresence(device(null), now)).toBe("AWAITING"));
  it("classifica heartbeat recente como ONLINE", () => {
    expect(classifyPresence(device(new Date(now - ONLINE_WINDOW_MS).toISOString()), now)).toBe("ONLINE");
  });
  it("classifica heartbeat expirado como OFFLINE", () => {
    expect(classifyPresence(device(new Date(now - ONLINE_WINDOW_MS - 1).toISOString()), now)).toBe("OFFLINE");
  });
  it("formata última comunicação sem expor timestamp técnico", () => {
    expect(formatLastCommunication(new Date(now - 30_000).toISOString(), now)).toBe("agora");
    expect(formatLastCommunication(new Date(now - 34 * 60_000).toISOString(), now)).toBe("há 34 min");
    expect(formatLastCommunication(null, now)).toBe("ainda não registrada");
  });
});
