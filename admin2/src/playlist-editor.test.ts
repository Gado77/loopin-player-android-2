import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DraftItem, MediaAsset } from "./types";
import { mediaItem, moveItem, normalizeItems, removeItem, weatherItem } from "./playlist-editor";

const asset = (media_type: "VIDEO"|"IMAGE"): MediaAsset => ({ id:"asset-1", name:"Arquivo", media_type, expected_size_bytes:1, sha256:"a".repeat(64), mime_type:media_type === "VIDEO" ? "video/mp4" : "image/png", storage_path:"path", created_at:"2026-01-01" });

describe("playlist editor", () => {
  beforeEach(() => vi.stubGlobal("crypto", { randomUUID: () => "item-id" }));

  it("creates video without artificial duration", () => expect(mediaItem(asset("VIDEO"), 10)).toEqual({ id:"item-id", order:0, kind:"MEDIA", assetId:"asset-1" }));
  it("requires a valid image duration", () => {
    expect(mediaItem(asset("IMAGE"), 10)).toMatchObject({ durationMs:10_000 });
    expect(() => mediaItem(asset("IMAGE"), 0)).toThrow("Duração");
    expect(() => mediaItem(asset("IMAGE"), 3601)).toThrow("Duração");
  });
  it("creates validated WEATHER items", () => expect(weatherItem(" Teresina ", "-5.09", "-42.80", 20)).toMatchObject({ kind:"DYNAMIC", durationMs:20_000, configuration:{ city:"Teresina", lat:"-5.09", lon:"-42.8" } }));
  it.each([["", "0", "0"],["City","-91","0"],["City","0","181"],["City","x","0"]])("rejects invalid WEATHER fields", (city,lat,lon) => expect(() => weatherItem(city,lat,lon,20)).toThrow());
  it("normalizes, moves and removes while preserving contiguous order", () => {
    const items = [{ id:"a",order:8,kind:"MEDIA",assetId:"a" },{ id:"b",order:9,kind:"MEDIA",assetId:"b" }] as DraftItem[];
    expect(normalizeItems(items).map(i=>i.order)).toEqual([0,1]);
    expect(moveItem(items,1,-1).map(i=>i.id)).toEqual(["b","a"]);
    expect(moveItem(items,0,-1).map(i=>i.id)).toEqual(["a","b"]);
    expect(removeItem(items,0)).toEqual([{ id:"b",order:0,kind:"MEDIA",assetId:"b" }]);
  });
});
