import { useEffect, useRef, useState } from "react";
import { diffSnapshots, pushEvents, type ActivityEvent, type FeedSnapshot } from "./activityFeed";

export const ACTIVITY_FEED_CAP = 50;

/**
 * In-memory ring buffer of activity events derived from consecutive
 * `FeedSnapshot`s (see activityFeed.ts for the diffing rules). Lives only as
 * long as the page is mounted: navigate away and the feed starts over from a
 * fresh baseline, which is honest -- there is no server-side event log to
 * backfill from, so a "history" would have to be made up.
 *
 * `snapshot` must be memoised by the caller; the diff runs whenever its
 * identity changes.
 */
export function useActivityFeed(snapshot: FeedSnapshot, cap = ACTIVITY_FEED_CAP): ActivityEvent[] {
  const [feed, setFeed] = useState<ActivityEvent[]>([]);
  const prevRef = useRef<FeedSnapshot | null>(null);

  useEffect(() => {
    const events = diffSnapshots(prevRef.current, snapshot, new Date().toISOString());
    prevRef.current = snapshot;
    if (events.length > 0) setFeed((current) => pushEvents(current, events, cap));
  }, [snapshot, cap]);

  return feed;
}
