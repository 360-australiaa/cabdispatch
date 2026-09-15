import { describe, expect, it } from "vitest";
import { buildDocketCsv, joinSettlementRef, splitSettlementRef } from "./format";
import type { PaymentRead } from "./types";

const ROW: PaymentRead = {
  id: "p1",
  tenant_id: "t1",
  trip_id: "trip-1",
  method: "cabcharge",
  amount: "59.03",
  surcharge: "2.95",
  stripe_pi_id: null,
  status: "succeeded",
  captured_at: "2026-09-14T08:04:00Z",
  change_given: null,
  docket_number: "CC-1001",
  notes: 'CabCharge authorization AUTH-9 (card 1234)\nSettlement ref: BATCH "A", sept',
  subsidy_amount: null,
  passenger_paid_amount: null,
  created_at: "2026-09-14T08:00:00Z",
  updated_at: "2026-09-14T08:04:00Z",
};

describe("settlement reference in notes", () => {
  it("round-trips a reference alongside other notes", () => {
    expect(splitSettlementRef(ROW.notes)).toEqual({
      reference: 'BATCH "A", sept',
      rest: "CabCharge authorization AUTH-9 (card 1234)",
    });
    expect(joinSettlementRef("CabCharge authorization AUTH-9 (card 1234)", 'BATCH "A", sept')).toBe(ROW.notes);
  });

  it("handles no reference, no notes, and clearing the reference", () => {
    expect(splitSettlementRef(null)).toEqual({ reference: "", rest: "" });
    expect(splitSettlementRef("just a note")).toEqual({ reference: "", rest: "just a note" });
    expect(joinSettlementRef("", "")).toBeNull();
    expect(joinSettlementRef("", "X1")).toBe("Settlement ref: X1");
    expect(joinSettlementRef("note", "")).toBe("note");
  });
});

describe("buildDocketCsv", () => {
  it("emits CabCharge columns with RFC 4180 quoting and CRLF line ends", () => {
    const csv = buildDocketCsv([ROW], "cabcharge");
    const lines = csv.split("\r\n");
    expect(lines[0]).toBe(
      "docket_number,trip_id,method,amount,surcharge,status,captured_at,settlement_ref,notes,created_at,payment_id",
    );
    expect(lines[1]).toBe(
      'CC-1001,trip-1,cabcharge,59.03,2.95,succeeded,2026-09-14T08:04:00Z,"BATCH ""A"", sept",CabCharge authorization AUTH-9 (card 1234),2026-09-14T08:00:00Z,p1',
    );
    expect(lines.at(-1)).toBe("");
  });

  it("adds the subsidy split for TTSS and leaves blanks for nulls", () => {
    const ttss: PaymentRead = {
      ...ROW,
      method: "ttss",
      docket_number: null,
      notes: null,
      captured_at: null,
      subsidy_amount: "29.52",
      passenger_paid_amount: "29.51",
    };
    const csv = buildDocketCsv([ttss], "ttss");
    const lines = csv.split("\r\n");
    expect(lines[0]).toContain("surcharge,subsidy_amount,passenger_paid_amount,status");
    expect(lines[1]).toBe(",trip-1,ttss,59.03,2.95,29.52,29.51,succeeded,,,,2026-09-14T08:00:00Z,p1");
  });
});
