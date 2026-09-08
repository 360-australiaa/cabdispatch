import { type FormEvent, useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "@/lib/auth";
import { Button, Card, CardContent, CardHeader, CardTitle, Input } from "@/components/ui";

/**
 * Platform-default branding, shown until a signed-in tenant's own
 * `theme_json` is available (see `components/layout/Sidebar.tsx`, which
 * *does* have a tenant to brand with, once the operator is signed in).
 *
 * HONESTY NOTE (audit §4, tenant naming): a real per-tenant login page — a
 * driver typing their own operator's name/logo before they've authenticated
 * at all — needs the *server* to know which tenant a bare `/login` request
 * is for (a subdomain, a `?tenant=slug` the backend can resolve to branding,
 * or similar), and there's no such public, unauthenticated endpoint today.
 * `POST /v1/auth/driver-login`'s `tenant_slug` (app/api/v1/auth.py) is the
 * closest analogue but it's a *login* call, not a branding lookup, and it's
 * the Android app's endpoint, not this dashboard's. Building that endpoint
 * is backend work this workstream doesn't own (`backend/**` is out of
 * bounds — see the program plan's Wave 3 constraints). So this page stays
 * generically branded and named as a constant rather than a scattered
 * literal, which is the honest amount of "tenant branding on login" this
 * workstream can deliver without adding an API.
 */
const BRAND_NAME = "Cab Dispatch Fleet Ops";
const BRAND_MARK = "CD";

export default function LoginPage() {
  const { login, completeMfaLogin, isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Two-step login: "credentials" is the original single-step form (unchanged
  // for accounts without MFA). "mfa" only appears when POST /v1/auth/login
  // returns { mfa_required: true, mfa_token } instead of tokens.
  const [step, setStep] = useState<"credentials" | "mfa">("credentials");
  const [mfaToken, setMfaToken] = useState<string | null>(null);
  const [mfaCode, setMfaCode] = useState("");
  const [mfaError, setMfaError] = useState<string | null>(null);
  const [isVerifyingMfa, setIsVerifyingMfa] = useState(false);

  if (isAuthenticated) {
    const from = (location.state as { from?: Location })?.from?.pathname ?? "/live-map";
    return <Navigate to={from} replace />;
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setIsSubmitting(true);
    try {
      const result = await login(email, password);
      if (result.mfaRequired) {
        setMfaToken(result.mfaToken);
        setMfaCode("");
        setMfaError(null);
        setStep("mfa");
      } else {
        navigate("/live-map", { replace: true });
      }
    } catch {
      setError("Invalid email or password.");
    } finally {
      setIsSubmitting(false);
    }
  }

  async function handleMfaSubmit(e: FormEvent) {
    e.preventDefault();
    if (!mfaToken) return;
    setMfaError(null);
    setIsVerifyingMfa(true);
    try {
      await completeMfaLogin(mfaToken, mfaCode);
      navigate("/live-map", { replace: true });
    } catch {
      setMfaError("Invalid or expired code. Try again.");
    } finally {
      setIsVerifyingMfa(false);
    }
  }

  function handleBackToCredentials() {
    setStep("credentials");
    setMfaToken(null);
    setMfaCode("");
    setMfaError(null);
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-brand-lavender p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <div className="mx-auto mb-2 flex h-10 w-10 items-center justify-center rounded-md bg-brand-primary font-bold text-brand-primary-foreground">
            {BRAND_MARK}
          </div>
          <CardTitle className="text-center text-lg">{BRAND_NAME}</CardTitle>
        </CardHeader>
        <CardContent>
          {step === "credentials" ? (
            <form onSubmit={handleSubmit} className="flex flex-col gap-4">
              <div className="flex flex-col gap-1.5">
                <label htmlFor="email" className="text-sm font-medium">
                  Email
                </label>
                <Input
                  id="email"
                  type="email"
                  autoComplete="username"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <label htmlFor="password" className="text-sm font-medium">
                  Password
                </label>
                <Input
                  id="password"
                  type="password"
                  autoComplete="current-password"
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </div>
              {error && <p className="text-sm text-destructive">{error}</p>}
              <Button type="submit" disabled={isSubmitting} className="mt-2">
                {isSubmitting ? "Signing in…" : "Sign in"}
              </Button>
            </form>
          ) : (
            <form onSubmit={handleMfaSubmit} className="flex flex-col gap-4">
              <p className="text-sm text-muted-foreground">
                Enter the 6-digit code from your authenticator app.
              </p>
              <div className="flex flex-col gap-1.5">
                <label htmlFor="mfa-code" className="text-sm font-medium">
                  Authentication code
                </label>
                <Input
                  id="mfa-code"
                  type="text"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={6}
                  required
                  autoFocus
                  value={mfaCode}
                  onChange={(e) => setMfaCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                  placeholder="123456"
                  className="text-center text-lg tracking-[0.5em]"
                />
              </div>
              {mfaError && <p className="text-sm text-destructive">{mfaError}</p>}
              <Button
                type="submit"
                disabled={isVerifyingMfa || mfaCode.length !== 6}
                className="mt-2"
              >
                {isVerifyingMfa ? "Verifying…" : "Verify & sign in"}
              </Button>
              <Button type="button" variant="ghost" onClick={handleBackToCredentials}>
                Back
              </Button>
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
