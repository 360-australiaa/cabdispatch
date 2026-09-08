import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import MfaQrCode from "./MfaQrCode";

describe("MfaQrCode", () => {
  it("renders a QR image whose src is a data: URL derived from the otpauth URI", async () => {
    render(<MfaQrCode otpauthUri="otpauth://totp/Cab%20Dispatch:test%40example.com?secret=ABCDEF" />);

    const img = await screen.findByAltText(/scan with your authenticator app/i);
    expect(img).toHaveAttribute("src", expect.stringMatching(/^data:image\/(png|svg\+xml)/));
  });
});
