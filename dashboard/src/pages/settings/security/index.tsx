import { type FormEvent, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, ShieldCheck, ShieldOff } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Input,
  Modal,
  PageHeader,
} from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { mfaDisable, mfaSetup, mfaVerify, setAdminPin } from "./api";
import type { MfaSetupResponse } from "./types";

/** Matches the backend's `_PIN_PATTERN` in `app/schemas/tenant.py` (4-8 digits). */
const ADMIN_PIN_PATTERN = /^\d{4,8}$/;

function errorMessage(error: unknown, fallback: string): string {
  return (error as { response?: { data?: { detail?: string } }; message?: string })?.response
    ?.data?.detail ?? (error as { message?: string })?.message ?? fallback;
}

export default function SecuritySettingsPage() {
  const { user, refreshUser } = useAuth();

  // Enable flow: null until `mfaSetup` returns a pending secret + otpauth
  // URI; the 6-digit verify form only appears once this is populated.
  const [pendingSetup, setPendingSetup] = useState<MfaSetupResponse | null>(null);
  const [verifyCode, setVerifyCode] = useState("");

  // Disable flow: password re-entry inside a confirmation modal.
  const [disableModalOpen, setDisableModalOpen] = useState(false);
  const [disablePassword, setDisablePassword] = useState("");

  // Admin PIN flow (owner-only, write-only — never re-displayed once saved).
  const [adminPin, setAdminPinValue] = useState("");
  const [adminPinConfirm, setAdminPinConfirm] = useState("");

  const setupMutation = useMutation({
    mutationFn: mfaSetup,
    onSuccess: (data) => {
      setPendingSetup(data);
      setVerifyCode("");
    },
  });

  const verifyMutation = useMutation({
    mutationFn: mfaVerify,
    onSuccess: async () => {
      setPendingSetup(null);
      setVerifyCode("");
      await refreshUser();
    },
  });

  const disableMutation = useMutation({
    mutationFn: mfaDisable,
    onSuccess: async () => {
      setDisableModalOpen(false);
      setDisablePassword("");
      await refreshUser();
    },
  });

  const adminPinMutation = useMutation({
    mutationFn: (pin: string) => setAdminPin(user!.tenant_id!, { pin }),
    onSuccess: () => {
      setAdminPinValue("");
      setAdminPinConfirm("");
    },
  });

  function handleStartSetup() {
    verifyMutation.reset();
    setupMutation.mutate();
  }

  function handleCancelSetup() {
    setPendingSetup(null);
    setVerifyCode("");
    setupMutation.reset();
    verifyMutation.reset();
  }

  function handleVerifySubmit(e: FormEvent) {
    e.preventDefault();
    if (verifyCode.length !== 6) return;
    verifyMutation.mutate({ code: verifyCode });
  }

  function handleDisableSubmit(e: FormEvent) {
    e.preventDefault();
    if (!disablePassword) return;
    disableMutation.mutate({ password: disablePassword });
  }

  function handleCloseDisableModal() {
    setDisableModalOpen(false);
    setDisablePassword("");
    disableMutation.reset();
  }

  const adminPinMismatch =
    adminPin.length > 0 && adminPinConfirm.length > 0 && adminPin !== adminPinConfirm;
  const adminPinValid = ADMIN_PIN_PATTERN.test(adminPin) && adminPin === adminPinConfirm;

  function handleAdminPinSubmit(e: FormEvent) {
    e.preventDefault();
    if (!adminPinValid) return;
    adminPinMutation.mutate(adminPin);
  }

  const mfaEnabled = user?.mfa_enabled ?? false;
  const isOwner = user?.role === "owner";

  return (
    <div>
      <PageHeader
        title="Security"
        description="Manage how you sign in to the Cab Dispatch dashboard."
      />

      <Card className="max-w-2xl">
        <CardHeader>
          <div className="flex items-center gap-2">
            <CardTitle>Two-factor authentication</CardTitle>
            <Badge variant={mfaEnabled ? "success" : "outline"}>
              {mfaEnabled ? "Enabled" : "Disabled"}
            </Badge>
          </div>
          <CardDescription>
            Require a 6-digit code from an authenticator app (Google Authenticator, Authy, 1Password,
            etc.) in addition to your password when signing in.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-5">
          {mfaEnabled && !pendingSetup && (
            <div className="flex items-start gap-3 rounded-md border border-border bg-muted/40 p-3">
              <ShieldCheck className="mt-0.5 h-5 w-5 shrink-0 text-success" />
              <div>
                <p className="text-sm font-medium text-foreground">
                  Two-factor authentication is on.
                </p>
                <p className="mt-1 text-sm text-muted-foreground">
                  You'll be asked for a code from your authenticator app every time you sign in.
                </p>
              </div>
            </div>
          )}

          {!mfaEnabled && !pendingSetup && (
            <div className="flex items-start gap-3 rounded-md border border-border bg-muted/40 p-3">
              <ShieldOff className="mt-0.5 h-5 w-5 shrink-0 text-muted-foreground" />
              <div>
                <p className="text-sm font-medium text-foreground">
                  Two-factor authentication is off.
                </p>
                <p className="mt-1 text-sm text-muted-foreground">
                  Turn it on to add a second step to sign-in.
                </p>
              </div>
            </div>
          )}

          {pendingSetup && (
            <div className="space-y-4 rounded-md border border-border p-4">
              <div>
                <p className="text-sm font-medium text-foreground">
                  1. Add this to your authenticator app
                </p>
                <p className="mt-1 text-sm text-muted-foreground">
                  Most apps let you paste a setup URI directly, or you can enter the secret
                  manually.
                </p>
              </div>

              <div>
                <label className="mb-1.5 block text-xs font-medium text-muted-foreground">
                  Setup URI (otpauth://)
                </label>
                <p className="break-all rounded-md border border-input bg-muted/40 p-2 font-mono text-xs text-foreground">
                  {pendingSetup.otpauth_uri}
                </p>
              </div>

              <div>
                <label className="mb-1.5 block text-xs font-medium text-muted-foreground">
                  Manual entry secret
                </label>
                <p className="break-all rounded-md border border-input bg-muted/40 p-2 font-mono text-xs text-foreground">
                  {pendingSetup.secret}
                </p>
              </div>

              <form onSubmit={handleVerifySubmit} className="space-y-2">
                <label htmlFor="verify-code" className="block text-sm font-medium text-foreground">
                  2. Enter the 6-digit code from the app to confirm
                </label>
                <div className="flex flex-wrap items-center gap-2">
                  <Input
                    id="verify-code"
                    type="text"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    maxLength={6}
                    autoFocus
                    value={verifyCode}
                    onChange={(e) => setVerifyCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                    placeholder="123456"
                    className="w-40 text-center text-lg tracking-[0.5em]"
                  />
                  <Button
                    type="submit"
                    disabled={verifyCode.length !== 6 || verifyMutation.isPending}
                  >
                    {verifyMutation.isPending ? "Verifying…" : "Verify & enable"}
                  </Button>
                  <Button type="button" variant="ghost" onClick={handleCancelSetup}>
                    Cancel
                  </Button>
                </div>
                {verifyMutation.isError && (
                  <p className="flex items-center gap-2 text-sm text-destructive">
                    <AlertTriangle className="h-4 w-4 shrink-0" />
                    {errorMessage(verifyMutation.error, "That code didn't verify. Try again.")}
                  </p>
                )}
              </form>
            </div>
          )}

          {setupMutation.isError && !pendingSetup && (
            <p className="flex items-center gap-2 text-sm text-destructive">
              <AlertTriangle className="h-4 w-4 shrink-0" />
              {errorMessage(setupMutation.error, "Couldn't start two-factor setup.")}
            </p>
          )}
          {disableMutation.isSuccess && (
            <p className="flex items-center gap-2 text-sm text-success">
              <CheckCircle2 className="h-4 w-4 shrink-0" />
              Two-factor authentication disabled.
            </p>
          )}
        </CardContent>

        <CardContent className="flex items-center gap-2 border-t border-border pt-4">
          {!mfaEnabled && !pendingSetup && (
            <Button onClick={handleStartSetup} disabled={setupMutation.isPending}>
              {setupMutation.isPending ? "Starting…" : "Enable two-factor authentication"}
            </Button>
          )}
          {mfaEnabled && (
            <Button
              variant="outline"
              className="text-destructive hover:bg-destructive/10"
              onClick={() => setDisableModalOpen(true)}
            >
              Disable two-factor authentication
            </Button>
          )}
        </CardContent>
      </Card>

      {isOwner && user?.tenant_id && (
        <Card className="mt-6 max-w-2xl">
          <CardHeader>
            <div className="flex items-center gap-2">
              <CardTitle>Admin PIN</CardTitle>
            </div>
            <CardDescription>
              This PIN protects the factory-reset option on drivers' Android tablets — a driver (or
              anyone with the tablet) needs it before the app will wipe itself. Only share it with
              people who should be able to do that. For security, the current PIN is never shown
              here — only whether your save succeeded.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleAdminPinSubmit} className="max-w-xs space-y-4">
              <div>
                <label
                  htmlFor="admin-pin"
                  className="mb-1.5 block text-sm font-medium text-foreground"
                >
                  New PIN (4-8 digits)
                </label>
                <Input
                  id="admin-pin"
                  type="text"
                  inputMode="numeric"
                  autoComplete="off"
                  maxLength={8}
                  value={adminPin}
                  onChange={(e) =>
                    setAdminPinValue(e.target.value.replace(/\D/g, "").slice(0, 8))
                  }
                  placeholder="••••"
                  className="text-center text-lg tracking-[0.3em]"
                />
              </div>
              <div>
                <label
                  htmlFor="admin-pin-confirm"
                  className="mb-1.5 block text-sm font-medium text-foreground"
                >
                  Confirm PIN
                </label>
                <Input
                  id="admin-pin-confirm"
                  type="text"
                  inputMode="numeric"
                  autoComplete="off"
                  maxLength={8}
                  value={adminPinConfirm}
                  onChange={(e) =>
                    setAdminPinConfirm(e.target.value.replace(/\D/g, "").slice(0, 8))
                  }
                  placeholder="••••"
                  className="text-center text-lg tracking-[0.3em]"
                />
              </div>

              {adminPinMismatch && (
                <p className="text-sm text-destructive">PINs don't match.</p>
              )}

              <Button type="submit" disabled={!adminPinValid || adminPinMutation.isPending}>
                {adminPinMutation.isPending ? "Saving…" : "Save admin PIN"}
              </Button>

              {adminPinMutation.isError && (
                <p className="flex items-center gap-2 text-sm text-destructive">
                  <AlertTriangle className="h-4 w-4 shrink-0" />
                  {errorMessage(adminPinMutation.error, "Couldn't save the admin PIN.")}
                </p>
              )}
              {adminPinMutation.isSuccess && (
                <p className="flex items-center gap-2 text-sm text-success">
                  <CheckCircle2 className="h-4 w-4 shrink-0" />
                  Admin PIN saved.
                </p>
              )}
            </form>
          </CardContent>
        </Card>
      )}

      <Modal
        open={disableModalOpen}
        onClose={handleCloseDisableModal}
        title="Disable two-factor authentication?"
        description="Re-enter your password to confirm. This removes the second step from your sign-in."
        footer={
          <>
            <Button type="button" variant="ghost" onClick={handleCloseDisableModal}>
              Cancel
            </Button>
            <Button
              type="submit"
              form="disable-mfa-form"
              variant="destructive"
              disabled={!disablePassword || disableMutation.isPending}
            >
              {disableMutation.isPending ? "Disabling…" : "Disable"}
            </Button>
          </>
        }
      >
        <form id="disable-mfa-form" onSubmit={handleDisableSubmit} className="flex flex-col gap-1.5">
          <label htmlFor="disable-password" className="text-sm font-medium">
            Password
          </label>
          <Input
            id="disable-password"
            type="password"
            autoComplete="current-password"
            autoFocus
            required
            value={disablePassword}
            onChange={(e) => setDisablePassword(e.target.value)}
          />
          {disableMutation.isError && (
            <p className="mt-1 flex items-center gap-2 text-sm text-destructive">
              <AlertTriangle className="h-4 w-4 shrink-0" />
              {errorMessage(disableMutation.error, "Incorrect password.")}
            </p>
          )}
        </form>
      </Modal>
    </div>
  );
}
