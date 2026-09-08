import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import { AuthProvider } from "@/lib/auth";
import LoginPage from "./index";

startMockServer();

beforeEach(() => {
  // A previous test's successful login writes real tokens to localStorage;
  // without clearing them, AuthProvider's mount effect in the NEXT test
  // fires an unhandled GET /v1/auth/me against the leftover token.
  localStorage.clear();
});

function renderLoginPage() {
  return render(
    <MemoryRouter initialEntries={["/login"]}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/live-map" element={<h1>Live map</h1>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

const USER = {
  id: "u1",
  tenant_id: "t1",
  role: "admin" as const,
  name: "Ops User",
  email: "ops@example.test",
  status: "active",
  mfa_enabled: true,
};

describe("LoginPage — MFA recovery code fallback", () => {
  it("lets a user switch to a recovery code and sends recovery_code (not code)", async () => {
    server.use(
      http.post(`${API}/v1/auth/login`, () =>
        HttpResponse.json({ mfa_required: true, mfa_token: "mfa-token-abc" }),
      ),
      http.get(`${API}/v1/tenants/me`, () =>
        HttpResponse.json({ id: "t1", name: "Test Tenant", theme_json: null }),
      ),
    );

    let receivedBody: Record<string, unknown> = {};
    server.use(
      http.post(`${API}/v1/auth/mfa/login`, async ({ request }) => {
        receivedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          access_token: "at",
          refresh_token: "rt",
          token_type: "bearer",
          user: USER,
        });
      }),
    );

    renderLoginPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/email/i), "ops@example.test");
    await user.type(screen.getByLabelText(/^password$/i), "Correct-Horse-1!");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    await screen.findByLabelText(/authentication code/i);
    await user.click(screen.getByRole("button", { name: /use a recovery code instead/i }));

    const recoveryInput = await screen.findByLabelText(/recovery code/i);
    await user.type(recoveryInput, "AAAA-BBBB-CCCC");
    await user.click(screen.getByRole("button", { name: /verify & sign in/i }));

    await screen.findByRole("heading", { name: "Live map" });
    expect(receivedBody).toEqual({
      mfa_token: "mfa-token-abc",
      code: undefined,
      recovery_code: "AAAA-BBBB-CCCC",
    });
  });

  it("has a Forgot password? link on the credentials step", async () => {
    renderLoginPage();
    expect(screen.getByRole("link", { name: /forgot password/i })).toHaveAttribute(
      "href",
      "/forgot-password",
    );
  });
});
