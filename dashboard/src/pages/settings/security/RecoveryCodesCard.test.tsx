import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import RecoveryCodesCard from "./RecoveryCodesCard";

startMockServer();

function renderCard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <RecoveryCodesCard />
    </QueryClientProvider>,
  );
}

const CODES = Array.from({ length: 10 }, (_, i) => `AAAA-BBBB-${String(i).padStart(4, "0")}`);

describe("RecoveryCodesCard", () => {
  it("shows the remaining-code count from the status endpoint", async () => {
    server.use(
      http.get(`${API}/v1/auth/mfa/recovery-codes/status`, () => HttpResponse.json({ remaining: 7 })),
    );

    renderCard();

    expect(await screen.findByText(/7 unused codes remaining/i)).toBeInTheDocument();
  });

  it("shows the ten fresh codes exactly once, after confirming generation", async () => {
    server.use(
      http.get(`${API}/v1/auth/mfa/recovery-codes/status`, () => HttpResponse.json({ remaining: 0 })),
      http.post(`${API}/v1/auth/mfa/recovery-codes/generate`, () =>
        HttpResponse.json({ codes: CODES }),
      ),
    );

    renderCard();
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: /generate recovery codes/i }));
    // Confirmation modal appears first — generating hasn't happened yet.
    await user.click(screen.getByRole("button", { name: /^generate$/i }));

    for (const code of CODES) {
      expect(await screen.findByText(code)).toBeInTheDocument();
    }
  });

  it("shows an error if generation fails", async () => {
    server.use(
      http.get(`${API}/v1/auth/mfa/recovery-codes/status`, () => HttpResponse.json({ remaining: 0 })),
      http.post(`${API}/v1/auth/mfa/recovery-codes/generate`, () =>
        HttpResponse.json({ detail: "Enable MFA first" }, { status: 400 }),
      ),
    );

    renderCard();
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: /generate recovery codes/i }));
    await user.click(screen.getByRole("button", { name: /^generate$/i }));

    expect(await screen.findByText(/enable mfa first/i)).toBeInTheDocument();
  });
});
