import { PageHeader } from "@/components/ui";

/**
 * Temporary stand-in rendered by every module route until the owning agent
 * replaces it with the real page at `src/pages/<module>/index.tsx`.
 */
export function PagePlaceholder({ title }: { title: string }) {
  return (
    <div>
      <PageHeader title={title} description="This module has not been built yet." />
      <div className="flex h-64 items-center justify-center rounded-lg border border-dashed border-border text-muted-foreground">
        {title} placeholder — implementation pending
      </div>
    </div>
  );
}
