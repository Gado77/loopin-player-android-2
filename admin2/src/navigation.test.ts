import { describe, expect, it } from "vitest";
import { ADMIN_AREAS, parseAdminArea } from "./navigation";

describe("Admin navigation", () => {
  it("exposes fleet groups and controlled updates", () => expect(ADMIN_AREAS).toEqual(["screens","media","playlists","groups","updates"]));
  it.each(["screens","media","playlists","groups","updates"])("accepts %s", (area) => expect(parseAdminArea(area)).toBe(area));
  it("falls back safely for unknown routes", () => expect(parseAdminArea("settings")).toBe("screens"));
});
