import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { MessageSquare, Search } from "lucide-react";
import { Badge, Card, CardContent, Input, PageHeader } from "@/components/ui";
import { cn } from "@/lib/utils";
import { listDriverOptions } from "./api";
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
                        <Badge variant={driver.on_shift ? "success" : "default"}>
                          {driver.on_shift ? "On shift" : "Off shift"}
                        </Badge>
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
