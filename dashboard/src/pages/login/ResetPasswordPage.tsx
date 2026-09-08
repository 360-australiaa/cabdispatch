import { type FormEvent, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useMutation } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import { Button, Card, CardContent, CardHeader, CardTitle, Input } from "@/components/ui";

/** D10: the landing page for the link `POST /v1/auth/password/reset/request`
 * emails — `?token=` is the single-use `reset_token` from that email,
 * exchanged here via `POST /v1/auth/password/reset/confirm`. */
export default function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get("token") ?? "";

  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");

  const mutation = useMutation({
    mutationFn: async () => {
      await apiClient.post("/v1/auth/password/reset/confirm", {
        reset_token: token,
        new_password: newPassword,
      });
    },
  });

  const mismatch = newPassword.length > 0 && confirmPassword.length > 0 && newPassword !== confirmPassword;
  const valid = Boolean(token) && newPassword.length >= 8 && newPassword === confirmPassword;

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!valid) return;
    mutation.mutate();
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-brand-lavender p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="text-center text-lg">Set a new password</CardTitle>
        </CardHeader>
        <CardContent>
          {!token ? (
            <p className="text-sm text-destructive">
              This reset link is missing its token. Request a new one from{" "}
              <Link to="/forgot-password" className="font-medium text-brand-primary hover:underline">
                the password reset page
              </Link>
              .
            </p>
          ) : mutation.isSuccess ? (
            <div className="space-y-4 text-center">
              <p className="text-sm text-foreground">
                Your password has been changed. Every device you were signed in on has been signed
                out for your security.
              </p>
              <Link to="/login" className="text-sm font-medium text-brand-primary hover:underline">
                Sign in
              </Link>
            </div>
          ) : (
            <form onSubmit={handleSubmit} className="flex flex-col gap-4">
              <div className="flex flex-col gap-1.5">
                <label htmlFor="new-password" className="text-sm font-medium">
                  New password
                </label>
                <Input
                  id="new-password"
                  type="password"
                  autoComplete="new-password"
                  required
                  minLength={8}
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                />
                <p className="text-xs text-muted-foreground">At least 8 characters.</p>
              </div>
              <div className="flex flex-col gap-1.5">
                <label htmlFor="confirm-password" className="text-sm font-medium">
                  Confirm new password
                </label>
                <Input
                  id="confirm-password"
                  type="password"
                  autoComplete="new-password"
                  required
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                />
              </div>
              {mismatch && <p className="text-sm text-destructive">Passwords don't match.</p>}
              {mutation.isError && (
                <p className="text-sm text-destructive">
                  That reset link is invalid or has expired — request a new one.
                </p>
              )}
              <Button type="submit" disabled={!valid || mutation.isPending} className="mt-2">
                {mutation.isPending ? "Saving…" : "Set new password"}
              </Button>
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
