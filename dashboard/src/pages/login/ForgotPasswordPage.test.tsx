import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import ForgotPasswordPage from "./ForgotPasswordPage";

startMockServer();

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ForgotPasswordPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("ForgotPasswordPage", () => {
  it("shows the same message for a submitted email regardless of whether the account exists", async () => {
    server.use(
      http.post(`${API}/v1/auth/password/reset/request`, () =>
        HttpResponse.json({ detail: "If that email is registered, a reset link has been sent." }, { status: 202 }),
      ),
    );

    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/email/i), "whoever@example.test");
    await user.click(screen.getByRole("button", { name: /send reset link/i }));

    expect(await screen.findByText(/if that email is registered/i)).toBeInTheDocument();
  });
});
