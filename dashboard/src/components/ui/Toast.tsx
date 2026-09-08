import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import { AlertTriangle, CheckCircle2, Info, X } from "lucide-react";
import { cn } from "@/lib/utils";

export type ToastVariant = "success" | "error" | "info";

export interface Toast {
  id: string;
  variant: ToastVariant;
  title: ReactNode;
  description?: ReactNode;
}

export interface ToastOptions {
  description?: ReactNode;
  /** Milliseconds before auto-dismiss. Pass `0` to require a manual dismiss. */
  duration?: number;
}

interface ToastContextValue {
  toast: (variant: ToastVariant, title: ReactNode, options?: ToastOptions) => string;
  success: (title: ReactNode, options?: ToastOptions) => string;
  error: (title: ReactNode, options?: ToastOptions) => string;
  info: (title: ReactNode, options?: ToastOptions) => string;
  dismiss: (id: string) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

/** Errors stay up until dismissed; successes clear themselves. */
const DEFAULT_DURATIONS: Record<ToastVariant, number> = {
  success: 4000,
  info: 5000,
  error: 0,
};

const VARIANT_STYLES: Record<ToastVariant, string> = {
  success: "border-success/40 bg-card text-foreground",
  error: "border-destructive/50 bg-card text-foreground",
  info: "border-border bg-card text-foreground",
};

const VARIANT_ICONS: Record<ToastVariant, typeof Info> = {
  success: CheckCircle2,
  error: AlertTriangle,
  info: Info,
};

const ICON_STYLES: Record<ToastVariant, string> = {
  success: "text-success",
  error: "text-destructive",
  info: "text-muted-foreground",
};

/**
 * App-level toast host.
 *
 * Why this exists: every mutation result in the dashboard was inline text next
 * to the form that triggered it. On the long pages (Platform, Billing, Fleet)
 * a successful save could land several screens above the fold, so the operator
 * got no feedback at all and would click Save again. A toast is fixed to the
 * viewport, so the confirmation is visible wherever the operator happens to be.
 *
 * Mount once, inside `AppShell`.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  // Timers are kept in a ref, not state, so that dismissing one toast does not
  // re-schedule the others' timeouts on every render.
  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>());

  const dismiss = useCallback((id: string) => {
    const timer = timers.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timers.current.delete(id);
    }
    setToasts((current) => current.filter((t) => t.id !== id));
  }, []);

  const toast = useCallback(
    (variant: ToastVariant, title: ReactNode, options?: ToastOptions) => {
      const id = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      setToasts((current) => [...current, { id, variant, title, description: options?.description }]);
      const duration = options?.duration ?? DEFAULT_DURATIONS[variant];
      if (duration > 0) {
        timers.current.set(
          id,
          setTimeout(() => dismiss(id), duration),
        );
      }
      return id;
    },
    [dismiss],
  );

  // Clearing on unmount stops a pending timeout from calling setState on a
  // provider that is gone (the classic logout-mid-toast warning).
  useEffect(() => {
    const pending = timers.current;
    return () => {
      pending.forEach((t) => clearTimeout(t));
      pending.clear();
    };
  }, []);

  const value = useMemo<ToastContextValue>(
    () => ({
      toast,
      dismiss,
      success: (title, options) => toast("success", title, options),
      error: (title, options) => toast("error", title, options),
      info: (title, options) => toast("info", title, options),
    }),
    [toast, dismiss],
  );

  return (
    <ToastContext.Provider value={value}>
      {children}
      <ToastViewport toasts={toasts} onDismiss={dismiss} />
    </ToastContext.Provider>
  );
}

function ToastViewport({ toasts, onDismiss }: { toasts: Toast[]; onDismiss: (id: string) => void }) {
  if (typeof document === "undefined") return null;

  return createPortal(
    // `role="region"` + `aria-live="polite"`: toasts are announced in turn
    // without cutting off whatever the operator is currently reading. Errors
    // that genuinely need to interrupt use `ErrorBanner` (role="alert") beside
    // the control that failed.
    <div
      role="region"
      aria-label="Notifications"
      aria-live="polite"
      className="pointer-events-none fixed bottom-4 right-4 z-[100] flex w-full max-w-sm flex-col gap-2"
    >
      {toasts.map(({ id, variant, title, description }) => {
        const Icon = VARIANT_ICONS[variant];
        return (
          <div
            key={id}
            className={cn(
              "pointer-events-auto flex items-start gap-2 rounded-lg border p-3 shadow-lg",
              VARIANT_STYLES[variant],
            )}
          >
            <Icon className={cn("mt-0.5 h-4 w-4 shrink-0", ICON_STYLES[variant])} aria-hidden="true" />
            <div className="flex-1 text-sm">
              <p className="font-medium">{title}</p>
              {description && <p className="mt-0.5 text-muted-foreground">{description}</p>}
            </div>
            <button
              type="button"
              onClick={() => onDismiss(id)}
              aria-label="Dismiss notification"
              className="rounded-md p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        );
      })}
    </div>,
    document.body,
  );
}

/**
 * Access the toast queue. Throws outside a `ToastProvider` rather than
 * silently no-op'ing, because a mutation that believes it reported success and
 * did not is exactly the "honesty over polish" failure this kit is meant to
 * remove.
 */
export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used within a <ToastProvider>");
  return ctx;
}
