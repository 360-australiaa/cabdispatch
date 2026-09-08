import { Component, type ErrorInfo, type ReactNode } from "react";
import { Button } from "@/components/ui";

interface ErrorBoundaryProps {
  children: ReactNode;
  /** Shown instead of the default panel. Receives the error and a reset callback. */
  fallback?: (error: Error, reset: () => void) => ReactNode;
  /** Side-channel for logging; the boundary itself never reports anywhere. */
  onError?: (error: Error, info: ErrorInfo) => void;
}

interface ErrorBoundaryState {
  error: Error | null;
}

/**
 * Catches a render-time throw and shows a recoverable panel instead of a
 * blank page.
 *
 * The dashboard had no error boundary anywhere (dashboard audit §6): React 18
 * unmounts the whole tree when a render throws, so a single bad field on one
 * page -- a `null` where a `.map()` was expected, a malformed date from the
 * API -- replaced the entire ops console with a white screen. An operator
 * watching a live map has no way to tell that from a crashed browser or a
 * dead network, and the only recovery was a manual reload.
 *
 * This has to be a class component: `componentDidCatch`/`getDerivedStateFrom
 * Error` have no hook equivalent, and that is still true in React 18. It is
 * the one class component in the codebase for exactly that reason.
 *
 * What it deliberately does NOT do:
 *
 *  - It does not report anywhere. There is no error-reporting service wired
 *    up in this app, and inventing one here would be a silent new dependency
 *    on a third party. `onError` is the seam for whoever adds one.
 *  - It does not swallow the error. `console.error` still runs, so the stack
 *    is in the console where a developer expects it.
 *  - It does not retry automatically. A render that threw once will usually
 *    throw again on the same props, and an automatic retry loop would spin.
 *    "Try again" is a button the human presses.
 *
 * Note the standard caveat, stated here so nobody assumes more coverage than
 * exists: an error boundary catches errors thrown *while rendering*, in
 * lifecycle methods, and in constructors below it. It does NOT catch errors
 * in event handlers, in `setTimeout`, or in promise rejections -- an API
 * failure inside a react-query hook surfaces as that hook's `error` state,
 * not here.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Keep the stack visible to a developer; the UI below stays human.
    console.error("Unhandled render error:", error, info.componentStack);
    this.props.onError?.(error, info);
  }

  reset = () => {
    this.setState({ error: null });
  };

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;

    if (this.props.fallback) return this.props.fallback(error, this.reset);

    return (
      <div
        role="alert"
        className="flex min-h-[60vh] flex-col items-center justify-center gap-4 p-8 text-center"
      >
        <div className="max-w-md">
          <h1 className="text-xl font-semibold text-foreground">This page hit an error</h1>
          <p className="mt-2 text-sm text-muted-foreground">
            Nothing you were viewing has been changed or lost. You can try this page again, or go
            back to the live map.
          </p>
          {/* The message, not the stack: enough for an operator to quote to
              support, without a wall of minified frames on screen. */}
          <p className="mt-4 break-words rounded-md border border-border bg-muted p-3 text-left font-mono text-xs text-muted-foreground">
            {error.message || "Unknown error"}
          </p>
        </div>
        <div className="flex gap-2">
          <Button onClick={this.reset}>Try again</Button>
          <Button variant="outline" onClick={() => window.location.assign("/live-map")}>
            Back to live map
          </Button>
        </div>
      </div>
    );
  }
}
