import { describe, expect, it } from "vitest";
import { buildAuditLogCsv } from "./csv";
import type { AuditLogEntry } from "./types";

function entry(overrides: Partial<AuditLogEntry>): AuditLogEntry {
  return {
    id: "entry-1",
    tenant_id: "tenant-1",
    actor_user_id: "user-1",
    action: "update",
    entity_type: "trip",
    entity_id: "trip-1",
    before_json: { status: "open" },
    after_json: { status: "closed" },
    at: "2026-01-01T00:00:00Z",
    hash: "hash-1",
    previous_hash: "hash-0",
    ...overrides,
  };
}

describe("buildAuditLogCsv", () => {
  it("states the applied filters and export scope in the file", () => {
    const csv = buildAuditLogCsv([entry({})], { action: "update", entity_type: "trip" }, {
      total: 1,
      truncated: false,
      exportedAt: "2026-01-02T00:00:00Z",
    });
    expect(csv).toContain("Action filter: update");
    expect(csv).toContain("Entity type filter: trip");
    expect(csv).toContain("Rows in this file: 1 of 1");
    expect(csv).not.toContain("WARNING");
  });

  it("flags a truncated export instead of presenting it as complete", () => {
    const csv = buildAuditLogCsv([entry({})], {}, {
      total: 500,
      truncated: true,
      exportedAt: "2026-01-02T00:00:00Z",
    });
    expect(csv).toContain("WARNING: this export was truncated");
  });

  it("labels a row whose prior state was not recorded rather than calling it a create", () => {
    const csv = buildAuditLogCsv(
      [entry({ action: "device_secret_rotated", before_json: null, entity_type: "device" })],
      {},
      { total: 1, truncated: false, exportedAt: "2026-01-02T00:00:00Z" },
    );
    expect(csv).toContain("Prior state not recorded -- cannot be diffed");
  });

  it("labels a genuine create row as Created", () => {
    const csv = buildAuditLogCsv(
      [entry({ action: "create", before_json: null })],
      {},
      { total: 1, truncated: false, exportedAt: "2026-01-02T00:00:00Z" },
    );
    expect(csv).toContain(",Created,");
  });
});
