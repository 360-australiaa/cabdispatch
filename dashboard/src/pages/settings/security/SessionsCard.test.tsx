import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import SessionsCard from "./SessionsCard";

startMockServer();

function renderCard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <SessionsCard />
    </QueryClientProvider>,
  );
}

const SESSIONS = [
  {
    id: "s1",
    user_agent: "Mozilla/5.0 Chrome/120",
    ip_address: "10.0.0.1",
    created_at: "2026-09-08T10:00:00Z",
    last_seen_at: "2026-09-08T10:05:00Z",
    is_current: true,
  },
  {
    id: "s2",
    user_agent: "Mozilla/5.0 Firefox/120",
    ip_address: "10.0.0.2",
    created_at: "2026-09-08T09:00:00Z",
    last_seen_at: "2026-09-08T09:30:00Z",
    is_current: false,
  },
];

describe("SessionsCard", () => {
  it("lists sessions and marks the current one", async () => {
    server.use(http.get(`${API}/v1/auth/sessions`, () => HttpResponse.json({ sessions: SESSIONS })));

    renderCard();

    expect(await screen.findByText("This device")).toBeInTheDocument();
    // Only the non-current session gets a Revoke button.
    expect(screen.getAllByRole("button", { name: /revoke/i })).toHaveLength(1);
  });

  it("revokes a single session and refreshes the list", async () => {
    let revoked = false;
    server.use(
      http.get(`${API}/v1/auth/sessions`, () =>
        HttpResponse.json({ sessions: revoked ? [SESSIONS[0]] : SESSIONS }),
      ),
      http.post(`${API}/v1/auth/sessions/s2/revoke`, () => {
        revoked = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderCard();
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: /revoke/i }));

    // The revoked session drops out of the (refetched) list.
    await screen.findByText("This device");
    expect(screen.queryByText(/firefox/i)).not.toBeInTheDocument();
  });

  it("sign-out-everywhere-else is disabled with only one session, and works with more", async () => {
    server.use(http.get(`${API}/v1/auth/sessions`, () => HttpResponse.json({ sessions: [SESSIONS[0]] })));
    renderCard();

    expect(await screen.findByRole("button", { name: /sign out everywhere else/i })).toBeDisabled();
  });

  it("sends keep_current=true by default when signing out everywhere else", async () => {
    let calledUrl: string | null = null;
    server.use(
      http.get(`${API}/v1/auth/sessions`, () => HttpResponse.json({ sessions: SESSIONS })),
      http.post(`${API}/v1/auth/sessions/revoke-all`, ({ request }) => {
        calledUrl = request.url;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderCard();
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: /sign out everywhere else/i }));
    // Two buttons now share this name: the (now-hidden-behind-the-modal)
    // trigger and the modal's confirm action — the confirm one is the one
    // added most recently.
    const buttons = screen.getAllByRole("button", { name: /sign out everywhere else/i });
    await user.click(buttons[buttons.length - 1]);

    await screen.findByText("This device");
    expect(calledUrl).toContain("keep_current=true");
  });
});
