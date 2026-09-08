import { type FormEvent, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import { Button, Card, CardContent, CardHeader, CardTitle, Input } from "@/components/ui";

/**
 * D10: `POST /v1/auth/password/reset/request`.
 *
 * The response is the SAME "if that email is registered…" message whether
 * or not the account exists — see the backend endpoint's own docstring and
 * `tests/test_auth_security_settings.py::test_reset_request_does_not_leak_account_existence`.
 * This page renders exactly that one message on success, with no branch on
 * whether the account was real — there is nothing for it to branch on, the
 * backend never says.
 */
export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");

  const mutation = useMutation({
    mutationFn: async (email: string) => {
      await apiClient.post("/v1/auth/password/reset/request", { email });
    },
  });

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!email) return;
    mutation.mutate(email);
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-brand-lavender p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="text-center text-lg">Reset your password</CardTitle>
        </CardHeader>
        <CardContent>
          {mutation.isSuccess ? (
            <div className="space-y-4 text-center">
              <p className="text-sm text-foreground">
                If that email is registered, a reset link has been sent. It expires in 30 minutes.
              </p>
              <Link to="/login" className="text-sm font-medium text-brand-primary hover:underline">
                Back to sign in
              </Link>
            </div>
          ) : (
            <form onSubmit={handleSubmit} className="flex flex-col gap-4">
              <p className="text-sm text-muted-foreground">
                Enter your email and we'll send you a link to reset your password.
              </p>
              <div className="flex flex-col gap-1.5">
                <label htmlFor="reset-email" className="text-sm font-medium">
                  Email
                </label>
                <Input
                  id="reset-email"
                  type="email"
                  autoComplete="username"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                />
              </div>
              {mutation.isError && (
                <p className="text-sm text-destructive">
                  Something went wrong. Try again in a moment.
                </p>
              )}
              <Button type="submit" disabled={mutation.isPending} className="mt-2">
                {mutation.isPending ? "Sending…" : "Send reset link"}
              </Button>
              <Link
                to="/login"
                className="text-center text-sm font-medium text-brand-primary hover:underline"
              >
                Back to sign in
              </Link>
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
