export type ScreenGridActions = {
  pair(screenId: string): void;
  assign(screenId: string, playlistVersionId: string, select: HTMLSelectElement): void | Promise<void>;
};

/** Delegation keeps controls functional when presence refresh replaces only grid.innerHTML. */
export function bindScreenGridEvents(grid: HTMLElement, actions: ScreenGridActions) {
  grid.addEventListener("click", (event) => {
    const button = (event.target as Element).closest<HTMLButtonElement>(".pair-button");
    if (!button || !grid.contains(button) || !button.dataset.id) return;
    actions.pair(button.dataset.id);
  });
  grid.addEventListener("change", (event) => {
    const select = (event.target as Element).closest<HTMLSelectElement>(".playlist-select");
    if (!select || !grid.contains(select) || !select.value || !select.dataset.screenId) return;
    void actions.assign(select.dataset.screenId, select.value, select);
  });
}
