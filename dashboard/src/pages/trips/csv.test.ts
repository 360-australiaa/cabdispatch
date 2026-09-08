import { describe, expect, it } from "vitest";
import { buildTripsCsv } from "./csv";
import type { Trip } from "@/hooks/useTrips";

function trip(overrides: Partial<Trip>): Trip {
  return {
    id: "trip-1",
    tenant_id: "tenant-1",
    client_uuid: "c-1",
    vehicle_id: "veh-1",
    driver_id: "drv-1",
    shift_id: null,
    tariff_id: "tariff-1",
    type: "rank_hail",
    simulated: false,
    status: "closed",
    time_class: "day",
    is_peak: false,
    maxi: false,
    passenger_count: 1,
    wheelchair_hiring: false,
    airport_rank_requested_maxi: false,
    voucher_code: null,
    account_reference: null,
    split_payments: null,
    start_at: "2026-01-01T00:00:00Z",
    end_at: "2026-01-01T00:10:00Z",
    start_lat: 0,
    start_lng: 0,
    end_lat: 0,
    end_lng: 0,
    distance_m: 1000,
    moving_s: 500,
    waiting_s: 100,
    flag_fall: "3.60",
    dist_amount: "5.00",
    wait_amount: "1.00",
    peak_amount: "0.00",
    tolls: "0.00",
    psl: "1.10",
    extras: "0.00",
    subtotal: "10.60",
    surcharge: "0.00",
    total: "12.60",
    gst_component: "1.15",
    payment_method: "cash",
    gps_trace_ref: null,
    max_fare_check_passed: true,
    variance_pct: "0.5",
    receipt_ref: "R-1",
    flagged_for_review: false,
    review_notes: null,
    created_at: "2026-01-01T00:00:00Z",
    updated_at: "2026-01-01T00:10:00Z",
    ...overrides,
  };
}

const meta = {
  exportedAt: "2026-01-02T00:00:00Z",
  filters: { Status: "closed" },
  fetchCapped: false,
  fetchLimit: 200,
  rowsFetched: 1,
  totalMatching: 1,
};

describe("buildTripsCsv", () => {
  it("formats money with the shared en-AU formatter, not the raw decimal string", () => {
    const csv = buildTripsCsv([trip({})], new Map(), new Map(), meta);
    expect(csv).toContain("$12.60");
    expect(csv).not.toMatch(/,12\.60,/);
  });

  it("leaves an open trip's total blank rather than $0.00", () => {
    const csv = buildTripsCsv([trip({ status: "open", total: "0.00" })], new Map(), new Map(), meta);
    const dataLine = csv.split("\r\n").find((l) => l.startsWith("trip-1"));
    expect(dataLine).toBeDefined();
    const cols = dataLine!.split(",");
    // total_aud is the 10th column (0-indexed 9)
    expect(cols[9]).toBe("");
  });

  it("warns in the file when the underlying fetch was capped", () => {
    const csv = buildTripsCsv([trip({})], new Map(), new Map(), {
      ...meta,
      fetchCapped: true,
      totalMatching: 500,
    });
    expect(csv).toContain("WARNING: the underlying fetch was capped");
  });

  it("resolves vehicle and driver labels when available", () => {
    const csv = buildTripsCsv(
      [trip({})],
      new Map([["veh-1", "ABC-123"]]),
      new Map([["drv-1", "Jane Doe"]]),
      meta,
    );
    expect(csv).toContain("ABC-123");
    expect(csv).toContain("Jane Doe");
  });
});
