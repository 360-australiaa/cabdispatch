import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, LogOut, Monitor } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  EmptyState,
  Modal,
  Spinner,
} from "@/components/ui";
import { formatDateTimeShort, relativeFromNow } from "@/lib/format";
import { listSessions, revokeAllSessions, revokeSession } from "./api";
import type { SessionRead } from "./types";

const SESSIONS_QUERY_KEY = ["auth-sessions"];

function errorMessage(error: unknown, fallback: string): string {
  return (error as { response?: { data?: { detail?: string } }; message?: string })?.response
    ?.data?.detail ?? (error as { message?: string })?.message ?? fallback;
}

/** A rough, best-effort device/browser label from the raw user-agent string
 * — this is a "does this look familiar" hint, not a fingerprint. */
function describeSession(session: SessionRead): string {
  const ua = session.user_agent ?? "";
  if (!ua) return "Unknown device";
  if (/mobile/i.test(ua)) return "Mobile browser";
  if (/chrome/i.test(ua)) return "Chrome";
  if (/firefox/i.test(ua)) return "Firefox";
  if (/safari/i.test(ua)) return "Safari";
  if (/edg/i.test(ua)) return "Edge";
  return "Browser";
}

/**
 * D10: "sessions" + "sign out everywhere". Built entirely on the backend's
 * existing revocation store (see `POST /v1/auth/sessions/*` in
 * `app/api/v1/auth.py`) — revoking a session here kills it EVERYWHERE,
 * including an already-open websocket connection (dashboard live-map, jobs,
 * messages, duress feeds all poll the revocation store while connected).
 */
export default function SessionsCard() {
  const queryClient = useQueryClient();
  const [signOutAllOpen, setSignOutAllOpen] = useState(false);

  const sessionsQuery = useQuery({
    queryKey: SESSIONS_QUERY_KEY,
    queryFn: listSessions,
  });

  const revokeMutation = useMutation({
    mutationFn: revokeSession,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: SESSIONS_QUERY_KEY }),
  });

  const revokeAllMutation = useMutation({
    mutationFn: () => revokeAllSessions(false),
    onSuccess: () => {
      setSignOutAllOpen(false);
      queryClient.invalidateQueries({ queryKey: SESSIONS_QUERY_KEY });
    },
  });

  const sessions = sessionsQuery.data?.sessions ?? [];
  const otherSessionsCount = sessions.filter((s) => !s.is_current).length;

  return (
    <Card className="mt-6 max-w-2xl">
      <CardHeader>
        <div className="flex items-center gap-2">
          <Monitor className="h-4 w-4 text-muted-foreground" />
          <CardTitle>Sessions</CardTitle>
        </div>
        <CardDescription>
          Everywhere you're currently signed in. Revoking a session ends it immediately, even if
          it's in the middle of something.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {sessionsQuery.isLoading && (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner className="h-4 w-4" /> Loading sessions…
          </div>
        )}

        {sessionsQuery.isError && (
          <p className="flex items-center gap-2 text-sm text-destructive">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            {errorMessage(sessionsQuery.error, "Couldn't load sessions.")}
          </p>
        )}

        {sessionsQuery.isSuccess && sessions.length === 0 && (
          <EmptyState title="No active sessions" description="This shouldn't happen while you're signed in." />
        )}

        {sessions.length > 0 && (
          <ul className="divide-y divide-border rounded-md border border-border">
            {sessions.map((session) => (
              <li key={session.id} className="flex items-center justify-between gap-3 p-3">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium text-foreground">
                      {describeSession(session)}
                    </span>
                    {session.is_current && <Badge variant="success">This device</Badge>}
                  </div>
                  <p className="mt-0.5 text-xs text-muted-foreground">
                    {session.ip_address ? `${session.ip_address} · ` : ""}
                    Signed in {formatDateTimeShort(session.created_at)} · active{" "}
                    {relativeFromNow(session.last_seen_at)}
                  </p>
                </div>
                {!session.is_current && (
                  <Button
                    variant="outline"
                    size="sm"
                    className="text-destructive hover:bg-destructive/10"
                    onClick={() => revokeMutation.mutate(session.id)}
                    disabled={revokeMutation.isPending}
                  >
                    Revoke
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}

        {revokeMutation.isError && (
          <p className="flex items-center gap-2 text-sm text-destructive">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            {errorMessage(revokeMutation.error, "Couldn't revoke that session.")}
          </p>
        )}
      </CardContent>

      <CardContent className="flex items-center gap-2 border-t border-border pt-4">
        <Button
          variant="outline"
          className="gap-2 text-destructive hover:bg-destructive/10"
          onClick={() => setSignOutAllOpen(true)}
          disabled={otherSessionsCount === 0}
        >
          <LogOut className="h-4 w-4" />
          Sign out everywhere else
        </Button>
      </CardContent>

      <Modal
        open={signOutAllOpen}
        onClose={() => setSignOutAllOpen(false)}
        title="Sign out everywhere else?"
        description={`This ends ${otherSessionsCount} other session${
          otherSessionsCount === 1 ? "" : "s"
        } immediately. This device stays signed in.`}
        footer={
          <>
            <Button type="button" variant="ghost" onClick={() => setSignOutAllOpen(false)}>
              Cancel
            </Button>
            <Button
              type="button"
              variant="destructive"
              onClick={() => revokeAllMutation.mutate()}
              disabled={revokeAllMutation.isPending}
            >
              {revokeAllMutation.isPending ? "Signing out…" : "Sign out everywhere else"}
            </Button>
          </>
        }
      >
        {revokeAllMutation.isError && (
          <p className="flex items-center gap-2 text-sm text-destructive">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            {errorMessage(revokeAllMutation.error, "Couldn't sign out other sessions.")}
          </p>
        )}
      </Modal>
    </Card>
  );
}
