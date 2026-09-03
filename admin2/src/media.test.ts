import { describe, expect, it } from "vitest";
import { hashFile, MEDIA_LIMITS, safeExtension, Sha256, validateMediaFile } from "./media";

const bytes = (text: string) => new TextEncoder().encode(text);

describe("media validation and integrity", () => {
  it.each([
    ["video/mp4", "VIDEO"], ["image/jpeg", "IMAGE"], ["image/png", "IMAGE"],
  ])("accepts %s", (type, expected) => {
    expect(validateMediaFile({ name: "file", type, size: 1 })).toBe(expected);
  });

  it("rejects unsupported, empty and oversized files", () => {
    expect(() => validateMediaFile({ name: " ", type: "image/png", size: 1 })).toThrow("Nome");
    expect(() => validateMediaFile({ name: "a".repeat(201), type: "image/png", size: 1 })).toThrow("Nome");
    expect(() => validateMediaFile({ name: "x.gif", type: "image/gif", size: 1 })).toThrow("Formato");
    expect(() => validateMediaFile({ name: "x.png", type: "image/png", size: 0 })).toThrow("vazio");
    expect(() => validateMediaFile({ name: "x.png", type: "image/png", size: MEDIA_LIMITS.IMAGE + 1 })).toThrow("20 MiB");
    expect(() => validateMediaFile({ name: "x.mp4", type: "video/mp4", size: MEDIA_LIMITS.VIDEO + 1 })).toThrow("300 MiB");
  });

  it("computes standard SHA-256 vectors incrementally", () => {
    expect(new Sha256().update(bytes("abc")).digestHex()).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    expect(new Sha256().update(bytes("a")).update(bytes("b")).update(bytes("c")).digestHex()).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });

  it("hashes a Blob in bounded chunks", async () => {
    await expect(hashFile(new Blob(["abc"]), 1)).resolves.toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });

  it.each([["video/mp4","mp4"],["image/png","png"],["image/jpeg","jpg"]])("maps %s extension", (mime, extension) => {
    expect(safeExtension(mime)).toBe(extension);
  });
});
