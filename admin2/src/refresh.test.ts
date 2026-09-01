import { afterEach, describe, expect, it, vi } from "vitest";
import { DashboardPresenceRefresher } from "./refresh";

describe("dashboard presence refresh", () => {
  afterEach(() => vi.useRealTimers());

  it("não cria múltiplos timers e atualiza em intervalo limitado", async () => {
    vi.useFakeTimers();
    const refresh = vi.fn().mockResolvedValue(undefined);
    const scheduler = new DashboardPresenceRefresher(refresh, () => true, 60_000);
    scheduler.start();
    scheduler.start();
    expect(vi.getTimerCount()).toBe(1);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(1);
    scheduler.stop();
  });

  it("suspende atualização quando a aba está escondida", async () => {
    vi.useFakeTimers();
    const refresh = vi.fn().mockResolvedValue(undefined);
    const scheduler = new DashboardPresenceRefresher(refresh, () => false, 60_000);
    scheduler.start();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(refresh).not.toHaveBeenCalled();
    scheduler.stop();
  });

  it("logout cancela o refresh pendente", async () => {
    vi.useFakeTimers();
    const refresh = vi.fn().mockResolvedValue(undefined);
    const scheduler = new DashboardPresenceRefresher(refresh, () => true, 60_000);
    scheduler.start();
    scheduler.stop();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(refresh).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });
});
