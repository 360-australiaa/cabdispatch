import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import ResetPasswordPage from "./ResetPasswordPage";

startMockServer();

function renderPage(path = "/reset-password?token=abc123") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <ResetPasswordPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("ResetPasswordPage", () => {
  it("submits the token from the URL plus the new password", async () => {
    let receivedBody: unknown;
    server.use(
      http.post(`${API}/v1/auth/password/reset/confirm`, async ({ request }) => {
        receivedBody = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/^new password$/i), "brand-new-pass-1");
    await user.type(screen.getByLabelText(/confirm new password/i), "brand-new-pass-1");
    await user.click(screen.getByRole("button", { name: /set new password/i }));

    expect(await screen.findByText(/password has been changed/i)).toBeInTheDocument();
    expect(receivedBody).toEqual({ reset_token: "abc123", new_password: "brand-new-pass-1" });
  });

  it("shows an error for an expired/invalid token", async () => {
    server.use(
      http.post(`${API}/v1/auth/password/reset/confirm`, () =>
        HttpResponse.json({ detail: "Invalid or expired reset link" }, { status: 400 }),
      ),
    );

    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/^new password$/i), "brand-new-pass-1");
    await user.type(screen.getByLabelText(/confirm new password/i), "brand-new-pass-1");
    await user.click(screen.getByRole("button", { name: /set new password/i }));

    expect(await screen.findByText(/invalid or has expired/i)).toBeInTheDocument();
  });

  it("refuses to submit without a token in the URL", () => {
    renderPage("/reset-password");

    expect(screen.getByText(/missing its token/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /set new password/i })).not.toBeInTheDocument();
  });
});
