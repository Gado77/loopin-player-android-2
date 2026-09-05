import { describe, expect, it } from "vitest";
import { ADMIN_AREAS, parseAdminArea } from "./navigation";

describe("Admin navigation", () => {
  it("exposes the controlled update area", () => expect(ADMIN_AREAS).toEqual(["screens","media","playlists","updates"]));
  it.each(["screens","media","playlists","updates"])("accepts %s", (area) => expect(parseAdminArea(area)).toBe(area));
  it("falls back safely for unknown routes", () => expect(parseAdminArea("settings")).toBe("screens"));
});
