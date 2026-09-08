import { describe, expect, it } from "vitest";
import { classifyEntry, diffJson } from "./diff";

describe("diffJson", () => {
  it("returns changed fields sorted by name", () => {
    const rows = diffJson({ b: 1, a: 1 }, { b: 2, a: 1 });
    expect(rows).toEqual([{ field: "b", from: 1, to: 2 }]);
  });

  it("includes fields only present on one side", () => {
    const rows = diffJson({ a: 1 }, { a: 1, b: 2 });
    expect(rows).toEqual([{ field: "b", from: undefined, to: 2 }]);
  });

  it("returns nothing when nothing changed", () => {
    expect(diffJson({ a: 1 }, { a: 1 })).toEqual([]);
  });
});

describe("classifyEntry", () => {
  it("treats a real create action with null before as 'create'", () => {
    const result = classifyEntry({
      action: "create",
      before_json: null,
      after_json: { status: "open" },
    });
    expect(result.status).toBe("create");
    expect(result.changes).toEqual([{ field: "status", from: undefined, to: "open" }]);
  });

  it("treats device_registered with null before as 'create'", () => {
    const result = classifyEntry({
      action: "device_registered",
      before_json: null,
      after_json: { android_id: "abc" },
    });
    expect(result.status).toBe("create");
  });

  // Regression: device_secret_rotated (backend/app/services/fleet.py:357-365)
  // logs an update to an EXISTING device but never captures the prior
  // secret, so before_json is null on a non-create action. The page used to
  // render this as "Created" -- a confidently wrong diff. It must instead
  // say the prior state was not recorded.
  it("does not treat a non-create action with null before as a create", () => {
    const result = classifyEntry({
      action: "device_secret_rotated",
      before_json: null,
      after_json: { android_id: "abc", vehicle_id: "veh-1" },
    });
    expect(result.status).toBe("before-not-recorded");
    expect(result.changes).toEqual([]);
  });

  it("treats a null after as a real delete, not a gap", () => {
    const result = classifyEntry({
      action: "delete",
      before_json: { status: "open" },
      after_json: null,
    });
    expect(result.status).toBe("delete");
    expect(result.changes).toEqual([{ field: "status", from: "open", to: undefined }]);
  });

  it("diffs a normal update with both sides present", () => {
    const result = classifyEntry({
      action: "update",
      before_json: { status: "open" },
      after_json: { status: "closed" },
    });
    expect(result.status).toBe("update");
    expect(result.changes).toEqual([{ field: "status", from: "open", to: "closed" }]);
  });
});
