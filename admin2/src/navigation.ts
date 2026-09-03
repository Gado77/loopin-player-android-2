export const ADMIN_AREAS = ["screens", "media", "playlists"] as const;
export type AdminArea = typeof ADMIN_AREAS[number];

export function parseAdminArea(value: string | undefined): AdminArea {
  return ADMIN_AREAS.find((area) => area === value) ?? "screens";
}
