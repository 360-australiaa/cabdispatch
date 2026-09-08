import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import PasswordCard from "./PasswordCard";

startMockServer();

function renderCard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <PasswordCard />
    </QueryClientProvider>,
  );
}

describe("PasswordCard", () => {
  it("submits current + new password and shows success", async () => {
    let receivedBody: unknown;
    server.use(
      http.post(`${API}/v1/auth/password/change`, async ({ request }) => {
        receivedBody = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderCard();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/current password/i), "old-pass-1");
    await user.type(screen.getByLabelText(/^new password$/i), "new-password-1");
    await user.type(screen.getByLabelText(/confirm new password/i), "new-password-1");
    await user.click(screen.getByRole("button", { name: /change password/i }));

    expect(await screen.findByText(/password changed/i)).toBeInTheDocument();
    expect(receivedBody).toEqual({
      current_password: "old-pass-1",
      new_password: "new-password-1",
    });
  });

  it("shows the server's error when the current password is wrong", async () => {
    server.use(
      http.post(`${API}/v1/auth/password/change`, () =>
        HttpResponse.json({ detail: "Invalid email or password" }, { status: 401 }),
      ),
    );

    renderCard();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/current password/i), "wrong");
    await user.type(screen.getByLabelText(/^new password$/i), "new-password-1");
    await user.type(screen.getByLabelText(/confirm new password/i), "new-password-1");
    await user.click(screen.getByRole("button", { name: /change password/i }));

    expect(await screen.findByText(/invalid email or password/i)).toBeInTheDocument();
  });

  it("disables submit while new passwords don't match", async () => {
    renderCard();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/current password/i), "old-pass-1");
    await user.type(screen.getByLabelText(/^new password$/i), "new-password-1");
    await user.type(screen.getByLabelText(/confirm new password/i), "something-else");

    await waitFor(() =>
      expect(screen.getByRole("button", { name: /change password/i })).toBeDisabled(),
    );
    expect(screen.getByText(/don't match/i)).toBeInTheDocument();
  });
});
