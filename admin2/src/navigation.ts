export const ADMIN_AREAS = ["screens", "media", "playlists", "groups", "updates"] as const;
export type AdminArea = typeof ADMIN_AREAS[number];

export function parseAdminArea(value: string | undefined): AdminArea {
  return ADMIN_AREAS.find((area) => area === value) ?? "screens";
}
