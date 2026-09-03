import { describe, expect, it } from "vitest";
import { ADMIN_AREAS, parseAdminArea } from "./navigation";

describe("Admin navigation", () => {
  it("exposes the three minimal content areas", () => expect(ADMIN_AREAS).toEqual(["screens","media","playlists"]));
  it.each(["screens","media","playlists"])("accepts %s", (area) => expect(parseAdminArea(area)).toBe(area));
  it("falls back safely for unknown routes", () => expect(parseAdminArea("settings")).toBe("screens"));
});
