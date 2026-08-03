import { useEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, Radio, Send, X } from "lucide-react";
import { Button, Card, CardContent, CardHeader, CardTitle } from "@/components/ui";
import { cn } from "@/lib/utils";
import { listThread, sendMessage } from "./api";
import { formatTime, initials, senderLabel } from "./format";
import { useMessagesLive } from "./useMessagesLive";
import type { DriverOption, Message, MessageListResponse } from "./types";

const THREAD_LIMIT = 50;

const LIVE_STATUS_LABEL: Record<string, string> = {
  idle: "Not connected",
  connecting: "Connecting…",
  open: "Live",
  closed: "Disconnected",
  error: "Connection error",
};

/**
 * Selected driver's message history + reply box. History loads once from
 * `GET /v1/messages?driver_id=`; new messages — both the dispatcher's own
 * sends and anything the driver sends back — arrive live over
 * `WS /v1/messages/live?driver_id=` via `useMessagesLive` and are merged into
 * the same React Query cache entry, de-duped by id, so the thread updates
 * without a manual refresh.
 */
export function ThreadPanel({
  driver,
  onClose,
}: {
  driver: DriverOption;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState("");
  const scrollRef = useRef<HTMLDivElement | null>(null);

  const queryKey = useMemo(() => ["messages-thread", driver.id] as const, [driver.id]);

  const threadQuery = useQuery({
    queryKey,
    queryFn: () => listThread({ driver_id: driver.id, limit: THREAD_LIMIT }),
  });

  const { status: liveStatus, lastMessage } = useMessagesLive(driver.id, true);

  function mergeMessage(message: Message) {
    queryClient.setQueryData<MessageListResponse | undefined>(queryKey, (prev) => {
      if (!prev) return prev;
      if (prev.items.some((m) => m.id === message.id)) return prev;
      return { ...prev, items: [...prev.items, message], total: prev.total + 1 };
    });
  }

  useEffect(() => {
    if (lastMessage && lastMessage.driver_id === driver.id) {
      mergeMessage(lastMessage);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lastMessage]);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight });
  }, [threadQuery.data?.items.length]);

  const sendMutation = useMutation({
    mutationFn: () => sendMessage({ driver_id: driver.id, body: draft.trim() }),
    onSuccess: (message) => {
      setDraft("");
      mergeMessage(message);
    },
  });

  function submitDraft() {
    if (!draft.trim() || sendMutation.isPending) return;
    sendMutation.mutate();
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    submitDraft();
  }

  function handleKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      submitDraft();
    }
  }

  const messages = threadQuery.data?.items ?? [];

  return (
    <Card className="flex h-[calc(100vh-10rem)] flex-col">
      <CardHeader className="flex-row items-center justify-between gap-2 space-y-0 border-b border-border">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-brand-lavender text-xs font-semibold text-brand-primary">
            {initials(driver.name)}
          </div>
          <div>
            <CardTitle className="text-base">{driver.name}</CardTitle>
            <p className="text-xs text-muted-foreground">{driver.phone || "No phone on file"}</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <span
            className={cn(
              "inline-flex items-center gap-1.5 text-xs font-medium",
              liveStatus === "open" && "text-success",
              liveStatus === "connecting" && "text-muted-foreground",
              (liveStatus === "closed" || liveStatus === "error") && "text-destructive",
              liveStatus === "idle" && "text-muted-foreground",
            )}
          >
            <Radio className={cn("h-3 w-3", liveStatus === "open" && "animate-pulse")} />
            {LIVE_STATUS_LABEL[liveStatus] ?? liveStatus}
          </span>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close thread"
            className="rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
      </CardHeader>

      <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-3">
        {threadQuery.isLoading ? (
          <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" /> Loading thread…
          </div>
        ) : threadQuery.isError ? (
          <p className="text-sm text-destructive">Failed to load this thread.</p>
        ) : messages.length === 0 ? (
          <p className="py-6 text-center text-sm text-muted-foreground">
            No messages yet — send the first one below.
          </p>
        ) : (
          <ul className="flex flex-col gap-3">
            {messages.map((m) => (
              <MessageBubble key={m.id} message={m} />
            ))}
          </ul>
        )}
      </div>

      <CardContent className="border-t border-border pt-3">
        <form onSubmit={handleSubmit} className="flex items-end gap-2">
          <textarea
            className="flex-1 resize-none rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            rows={2}
            maxLength={4000}
            placeholder={`Message ${driver.name}…`}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={handleKeyDown}
          />
          <Button
            type="submit"
            variant="primary"
            disabled={!draft.trim() || sendMutation.isPending}
            aria-label="Send message"
          >
            {sendMutation.isPending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Send className="h-4 w-4" />
            )}
          </Button>
        </form>
        {sendMutation.isError && (
          <p className="mt-1 text-xs text-destructive">Failed to send — try again.</p>
        )}
      </CardContent>
    </Card>
  );
}

function MessageBubble({ message }: { message: Message }) {
  const isDispatch = message.sender_type === "dispatch";
  return (
    <li className={cn("flex", isDispatch ? "justify-end" : "justify-start")}>
      <div
        className={cn(
          "max-w-[80%] rounded-lg px-3 py-2 text-sm",
          isDispatch ? "bg-brand-primary text-brand-primary-foreground" : "bg-muted text-foreground",
        )}
      >
        <p className="whitespace-pre-wrap">{message.body}</p>
        <p
          className={cn(
            "mt-1 text-[11px]",
            isDispatch ? "text-brand-primary-foreground/70" : "text-muted-foreground",
          )}
        >
          {senderLabel(message.sender_type)} · {formatTime(message.sent_at)}
        </p>
      </div>
    </li>
  );
}
