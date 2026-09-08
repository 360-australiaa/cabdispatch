import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { AlertTriangle, KeyRound } from "lucide-react";
import { Button, Card, CardContent, CardDescription, CardHeader, CardTitle, Modal } from "@/components/ui";
import { generateRecoveryCodes, getRecoveryCodeStatus } from "./api";

function errorMessage(error: unknown, fallback: string): string {
  return (error as { response?: { data?: { detail?: string } }; message?: string })?.response
    ?.data?.detail ?? (error as { message?: string })?.message ?? fallback;
}

/**
 * D10: MFA recovery codes. Only meaningful once two-factor is enabled — the
 * caller (`index.tsx`) doesn't render this card at all otherwise.
 *
 * The ten plaintext codes exist in this component's state for exactly as
 * long as the "here are your new codes" modal is open, sourced ONLY from
 * `generateRecoveryCodes`'s direct response — never re-fetched, never
 * written to localStorage/sessionStorage, never logged. Closing the modal
 * drops them; only `remaining` (a count) is ever queried again.
 */
export default function RecoveryCodesCard() {
  const [freshCodes, setFreshCodes] = useState<string[] | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const statusQuery = useQuery({
    queryKey: ["mfa-recovery-codes-status"],
    queryFn: getRecoveryCodeStatus,
  });

  const generateMutation = useMutation({
    mutationFn: generateRecoveryCodes,
    onSuccess: (data) => {
      setFreshCodes(data.codes);
      setConfirmOpen(false);
      statusQuery.refetch();
    },
  });

  function handleConfirmGenerate() {
    generateMutation.mutate();
  }

  const remaining = statusQuery.data?.remaining;

  return (
    <Card className="mt-6 max-w-2xl">
      <CardHeader>
        <div className="flex items-center gap-2">
          <KeyRound className="h-4 w-4 text-muted-foreground" />
          <CardTitle>Recovery codes</CardTitle>
        </div>
        <CardDescription>
          One-time backup codes for signing in if you lose access to your authenticator app. Each
          code works once. Generating a new batch invalidates every code from the previous one.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {remaining !== undefined && (
          <p className="text-sm text-muted-foreground">
            {remaining} unused code{remaining === 1 ? "" : "s"} remaining.
          </p>
        )}

        <Button
          variant="outline"
          onClick={() => setConfirmOpen(true)}
          disabled={generateMutation.isPending}
        >
          {remaining === undefined || remaining === 0
            ? "Generate recovery codes"
            : "Generate new recovery codes"}
        </Button>

        {generateMutation.isError && (
          <p className="flex items-center gap-2 text-sm text-destructive">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            {errorMessage(generateMutation.error, "Couldn't generate recovery codes.")}
          </p>
        )}
      </CardContent>

      <Modal
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        title={
          remaining && remaining > 0 ? "Replace your recovery codes?" : "Generate recovery codes?"
        }
        description={
          remaining && remaining > 0
            ? "Every code from your current batch will stop working, including any you saved earlier."
            : "You'll see the ten codes exactly once — save them somewhere safe."
        }
        footer={
          <>
            <Button type="button" variant="ghost" onClick={() => setConfirmOpen(false)}>
              Cancel
            </Button>
            <Button type="button" onClick={handleConfirmGenerate} disabled={generateMutation.isPending}>
              {generateMutation.isPending ? "Generating…" : "Generate"}
            </Button>
          </>
        }
      />

      <Modal
        open={freshCodes !== null}
        onClose={() => setFreshCodes(null)}
        title="Your recovery codes"
        description="Save these somewhere safe — they won't be shown again. Each code can be used once."
        footer={
          <Button type="button" onClick={() => setFreshCodes(null)}>
            Done — I've saved these
          </Button>
        }
      >
        <div className="grid grid-cols-2 gap-2 rounded-md border border-border bg-muted/40 p-3 font-mono text-sm">
          {freshCodes?.map((code) => (
            <span key={code}>{code}</span>
          ))}
        </div>
      </Modal>
    </Card>
  );
}
