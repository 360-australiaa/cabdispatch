import { useState } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ErrorBoundary } from "./ErrorBoundary";

/** Throws on demand so a test can flip a subtree from working to broken. */
function Boom({ explode, message = "kaboom" }: { explode: boolean; message?: string }) {
  if (explode) throw new Error(message);
  return <p>All good</p>;
}

describe("ErrorBoundary", () => {
  // React logs every caught error to console.error, and the boundary logs its
  // own. Both are wanted in production and noise in a test run.
  let consoleError: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => {
    consoleError.mockRestore();
  });

  it("renders its children untouched when nothing throws", () => {
    render(
      <ErrorBoundary>
        <Boom explode={false} />
      </ErrorBoundary>,
    );

    expect(screen.getByText("All good")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("catches a render throw and shows a recoverable panel instead of a blank page", () => {
    render(
      <ErrorBoundary>
        <Boom explode />
      </ErrorBoundary>,
    );

    const alert = screen.getByRole("alert");
    expect(alert).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "This page hit an error" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Back to live map" })).toBeInTheDocument();
  });

  it("shows the error message, so an operator can quote it", () => {
    render(
      <ErrorBoundary>
        <Boom explode message="Cannot read properties of null (reading 'map')" />
      </ErrorBoundary>,
    );

    expect(
      screen.getByText("Cannot read properties of null (reading 'map')"),
    ).toBeInTheDocument();
  });

  it("falls back to a placeholder when the error carries no message", () => {
    function Empty(): never {
      throw new Error("");
    }
    render(
      <ErrorBoundary>
        <Empty />
      </ErrorBoundary>,
    );

    expect(screen.getByText("Unknown error")).toBeInTheDocument();
  });

  it("still logs the error rather than swallowing it", () => {
    render(
      <ErrorBoundary>
        <Boom explode message="visible in console" />
      </ErrorBoundary>,
    );

    expect(consoleError).toHaveBeenCalled();
    const logged = consoleError.mock.calls.some((call) =>
      call.some((arg) => arg instanceof Error && arg.message === "visible in console"),
    );
    expect(logged).toBe(true);
  });

  it("hands the error and component stack to onError", () => {
    const onError = vi.fn();
    render(
      <ErrorBoundary onError={onError}>
        <Boom explode message="reportable" />
      </ErrorBoundary>,
    );

    expect(onError).toHaveBeenCalledTimes(1);
    const [error, info] = onError.mock.calls[0];
    expect(error).toBeInstanceOf(Error);
    expect(error.message).toBe("reportable");
    expect(info).toHaveProperty("componentStack");
  });

  it("renders a custom fallback when given one", () => {
    render(
      <ErrorBoundary fallback={(error) => <p>Custom: {error.message}</p>}>
        <Boom explode message="nope" />
      </ErrorBoundary>,
    );

    expect(screen.getByText("Custom: nope")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("recovers on 'Try again' once the underlying cause is gone", async () => {
    // Mirrors the real recovery path: the boundary clears its error, and the
    // subtree re-renders with whatever state the app is now in.
    function Harness() {
      const [explode, setExplode] = useState(true);
      return (
        <>
          <button type="button" onClick={() => setExplode(false)}>
            Fix it
          </button>
          <ErrorBoundary>
            <Boom explode={explode} />
          </ErrorBoundary>
        </>
      );
    }
    render(<Harness />);
    expect(screen.getByRole("alert")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Fix it" }));
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));

    expect(screen.getByText("All good")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("re-catches when the cause is still there after 'Try again'", async () => {
    render(
      <ErrorBoundary>
        <Boom explode />
      </ErrorBoundary>,
    );

    await userEvent.click(screen.getByRole("button", { name: "Try again" }));

    // No automatic retry loop, and no blank page either: the panel comes back.
    expect(screen.getByRole("alert")).toBeInTheDocument();
  });

  it("sends the user to the live map from the panel", async () => {
    const assign = vi.fn();
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { pathname: "/trips", assign },
    });
    render(
      <ErrorBoundary>
        <Boom explode />
      </ErrorBoundary>,
    );

    await userEvent.click(screen.getByRole("button", { name: "Back to live map" }));

    expect(assign).toHaveBeenCalledWith("/live-map");
  });
});
