import { useMemo, useState } from "react";
import { useQueries, useQuery } from "@tanstack/react-query";
import { MessageSquare, Search } from "lucide-react";
import { Badge, Card, CardContent, Input, PageHeader } from "@/components/ui";
import { cn } from "@/lib/utils";
import { listDriverOptions, listLatestThread, THREAD_LIMIT } from "./api";
import { initials } from "./format";
import { ThreadPanel } from "./ThreadPanel";
import type { DriverOption } from "./types";

/**
 * Messages — dispatch<->driver threads (`POST/GET /v1/messages`,
 * `WS /v1/messages/live?driver_id=`). The backend has no "which drivers have
 * an existing thread" endpoint (a thread is just `Message` rows sharing a
 * `driver_id`, created lazily on first send), so the left rail is a driver
 * picker sourced from `GET /v1/drivers` — the same read-only rollup
 * `src/pages/fleet/DriversPanel.tsx` uses — rather than a thread list. Every
 * driver is reachable whether or not dispatch has messaged them yet.
 */
export default function MessagesPage() {
  const [search, setSearch] = useState("");
  const [selectedDriver, setSelectedDriver] = useState<DriverOption | null>(null);

  const driversQuery = useQuery({
    queryKey: ["messages-driver-options"],
    queryFn: listDriverOptions,
    refetchInterval: 30_000,
  });

  const filtered = useMemo(() => {
    const drivers = driversQuery.data ?? [];
    const q = search.trim().toLowerCase();
    if (!q) return drivers;
    return drivers.filter(
      (d) => d.name.toLowerCase().includes(q) || (d.phone ?? "").toLowerCase().includes(q),
    );
  }, [driversQuery.data, search]);

  // Per-driver unread badge: no backend endpoint exists for "unread count per
  // driver", so this fans out a lightweight `listLatestThread` fetch per
  // listed driver (same query key/fetcher `ThreadPanel` uses, so opening a
  // thread reuses this cache instead of double-fetching) and counts
  // `read_at == null` driver-sent messages in that recent window. Same
  // "good enough for demo/dev fleet scale" tradeoff as this page's
  // `DRIVER_LOOKUP_LIMIT` — a real aggregate endpoint would be needed to do
  // this cheaply at larger fleet scale.
  const unreadQueries = useQueries({
    queries: filtered.map((driver) => ({
      queryKey: ["messages-thread", driver.id] as const,
      queryFn: () => listLatestThread(driver.id, THREAD_LIMIT),
      staleTime: 20_000,
      refetchInterval: 30_000,
    })),
  });

  const unreadCounts = useMemo(() => {
    const map = new Map<string, number>();
    filtered.forEach((driver, i) => {
      const data = unreadQueries[i]?.data;
      if (!data) return;
      const count = data.items.filter((m) => m.sender_type === "driver" && m.read_at === null).length;
      if (count > 0) map.set(driver.id, count);
    });
    return map;
  }, [filtered, unreadQueries]);

  return (
    <div>
      <PageHeader
        title="Messages"
        description="Dispatch <-> driver threads, delivered live over each driver's own message channel."
      />

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <Card className="flex h-[calc(100vh-10rem)] flex-col lg:col-span-1">
          <CardContent className="flex flex-1 flex-col gap-3 overflow-hidden pt-4">
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                className="pl-9"
                placeholder="Search drivers…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            </div>

            {driversQuery.isError && (
              <p className="text-sm text-destructive">Failed to load drivers.</p>
            )}

            <div className="-mx-4 flex-1 overflow-y-auto">
              {driversQuery.isLoading ? (
                <p className="px-4 py-6 text-center text-sm text-muted-foreground">
                  Loading drivers…
                </p>
              ) : filtered.length === 0 ? (
                <p className="px-4 py-6 text-center text-sm text-muted-foreground">
                  {search ? `No drivers match "${search}".` : "No drivers found."}
                </p>
              ) : (
                <ul>
                  {filtered.map((driver) => (
                    <li key={driver.id}>
                      <button
                        type="button"
                        onClick={() => setSelectedDriver(driver)}
                        className={cn(
                          "flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm hover:bg-muted/60",
                          selectedDriver?.id === driver.id && "bg-muted",
                        )}
                      >
                        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-brand-lavender text-xs font-semibold text-brand-primary">
                          {initials(driver.name)}
                        </div>
                        <div className="min-w-0 flex-1">
                          <p className="truncate font-medium text-foreground">{driver.name}</p>
                          <p className="truncate text-xs text-muted-foreground">
                            {driver.phone || "No phone on file"}
                          </p>
                        </div>
                        <div className="flex shrink-0 flex-col items-end gap-1">
                          <Badge variant={driver.on_shift ? "success" : "default"}>
                            {driver.on_shift ? "On shift" : "Off shift"}
                          </Badge>
                          {(unreadCounts.get(driver.id) ?? 0) > 0 && (
                            <Badge
                              variant="destructive"
                              aria-label={`${unreadCounts.get(driver.id)} unread message${unreadCounts.get(driver.id) === 1 ? "" : "s"}`}
                            >
                              {unreadCounts.get(driver.id)} unread
                            </Badge>
                          )}
                        </div>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </CardContent>
        </Card>

        <div className="lg:col-span-2">
          {selectedDriver ? (
            <ThreadPanel driver={selectedDriver} onClose={() => setSelectedDriver(null)} />
          ) : (
            <Card className="flex h-[calc(100vh-10rem)] items-center justify-center">
              <CardContent className="flex flex-col items-center gap-2 pt-4 text-center text-sm text-muted-foreground">
                <MessageSquare className="h-8 w-8 text-muted-foreground/50" />
                Select a driver to view their thread.
              </CardContent>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
