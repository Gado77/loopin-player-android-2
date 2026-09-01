import { describe, expect, it, vi } from "vitest";
import { bindScreenGridEvents } from "./screen-grid-events";

describe("screen grid event delegation", () => {
  it("mantém ações nos controles criados depois do refresh por innerHTML", () => {
    const listeners = new Map<string, (event: Event) => void>();
    const grid = {
      addEventListener: (name: string, listener: (event: Event) => void) => listeners.set(name, listener),
      contains: () => true,
    } as unknown as HTMLElement;
    const pair = vi.fn();
    const assign = vi.fn();
    bindScreenGridEvents(grid, { pair, assign });

    const refreshedPairButton = { dataset: { id: "screen-after-refresh" } };
    listeners.get("click")!({ target: { closest: () => refreshedPairButton } } as unknown as Event);
    const refreshedSelect = { dataset: { screenId: "screen-after-refresh" }, value: "version-2" };
    listeners.get("change")!({ target: { closest: () => refreshedSelect } } as unknown as Event);

    expect(pair).toHaveBeenCalledWith("screen-after-refresh");
    expect(assign).toHaveBeenCalledWith("screen-after-refresh", "version-2", refreshedSelect);
  });
});
