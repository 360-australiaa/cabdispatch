import { describe, expect, it } from "vitest";
import { translate } from "./index";
import { getJurisdictionCapabilities } from "./jurisdiction";
import { COUNTRY_DIAL_CODES, isValidE164, joinE164, splitE164 } from "./phone";

describe("translate", () => {
  it("returns the dictionary value for a known key", () => {
    expect(translate("common.cancel")).toBe("Cancel");
  });

  it("interpolates placeholders", () => {
    expect(translate("ui.pagination.pageOf", { page: 2, total: 5 })).toBe("Page 2 of 5");
  });

  it("renders a visibly-missing marker for an unknown key rather than falling back silently", () => {
    // @ts-expect-error -- deliberately an invalid key to exercise the fallback.
    expect(translate("no.such.key")).toBe("⟦missing:no.such.key⟧");
  });
});

describe("getJurisdictionCapabilities", () => {
  it("returns every NSW-only capability enabled for any tenant today (no backend jurisdiction field yet)", () => {
    expect(getJurisdictionCapabilities(null)).toEqual({
      psl: true,
      nswTollRoads: true,
      nswPtpExport: true,
    });
    expect(getJurisdictionCapabilities({ id: "t1" })).toEqual({
      psl: true,
      nswTollRoads: true,
      nswPtpExport: true,
    });
  });
});

describe("phone (E.164)", () => {
  it("validates full E.164 syntax", () => {
    expect(isValidE164("+61412345678")).toBe(true);
    expect(isValidE164("0412345678")).toBe(false);
    expect(isValidE164("+")).toBe(false);
    expect(isValidE164("++61412345678")).toBe(false);
  });

  it("joins a country + national number into E.164, stripping a leading trunk zero", () => {
    expect(joinE164("AU", "0412 345 678")).toBe("+61412345678");
    expect(joinE164("US", "212-555-0100")).toBe("+12125550100");
  });

  it("splits a known E.164 value back into country + national number", () => {
    expect(splitE164("+61412345678")).toEqual({ iso: "AU", national: "412345678" });
  });

  it("returns null for a value that isn't valid E.164 or isn't in the known country list", () => {
    expect(splitE164("0412345678")).toBeNull();
    expect(splitE164("+998912345678")).toBeNull(); // Uzbekistan -- not in COUNTRY_DIAL_CODES
  });

  it("round-trips every listed country's dial code", () => {
    for (const country of COUNTRY_DIAL_CODES) {
      const e164 = joinE164(country.iso, "5551234");
      expect(splitE164(e164)?.iso).toBe(country.iso);
    }
  });
});
