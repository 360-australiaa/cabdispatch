import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ToastProvider, useToast } from "./Toast";

/** A button per variant, so a test can fire one and assert what appears. */
function Trigger() {
  const { success, error, info } = useToast();
  return (
    <>
      <button type="button" onClick={() => success("Saved", { description: "Tariff published" })}>
        save
      </button>
      <button type="button" onClick={() => error("Failed to save")}>
        fail
      </button>
      <button type="button" onClick={() => info("Syncing", { duration: 0 })}>
        sync
      </button>
    </>
  );
}

function renderWithProvider() {
  return render(
    <ToastProvider>
      <Trigger />
    </ToastProvider>,
  );
}

describe("Toast", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("shows a toast with its title and description", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProvider();

    await user.click(screen.getByRole("button", { name: "save" }));

    expect(screen.getByText("Saved")).toBeInTheDocument();
    expect(screen.getByText("Tariff published")).toBeInTheDocument();
  });

  it("puts toasts in a polite live region, so a save is announced", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProvider();

    await user.click(screen.getByRole("button", { name: "save" }));

    const region = screen.getByRole("region", { name: "Notifications" });
    expect(region).toHaveAttribute("aria-live", "polite");
    expect(region).toHaveTextContent("Saved");
  });

  it("auto-dismisses a success but keeps an error until acknowledged", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProvider();

    await user.click(screen.getByRole("button", { name: "save" }));
    await user.click(screen.getByRole("button", { name: "fail" }));

    act(() => {
      vi.advanceTimersByTime(6000);
    });

    // A failure the operator has not seen must not vanish on a timer -- that
    // is how a silent mutation failure looks like a success.
    expect(screen.queryByText("Saved")).not.toBeInTheDocument();
    expect(screen.getByText("Failed to save")).toBeInTheDocument();
  });

  it("honours duration 0 as 'stay until dismissed'", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProvider();

    await user.click(screen.getByRole("button", { name: "sync" }));
    act(() => {
      vi.advanceTimersByTime(60_000);
    });

    expect(screen.getByText("Syncing")).toBeInTheDocument();
  });

  it("dismisses on the close button", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProvider();
    await user.click(screen.getByRole("button", { name: "fail" }));

    await user.click(screen.getByRole("button", { name: "Dismiss notification" }));

    expect(screen.queryByText("Failed to save")).not.toBeInTheDocument();
  });

  it("stacks several toasts at once", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProvider();

    await user.click(screen.getByRole("button", { name: "fail" }));
    await user.click(screen.getByRole("button", { name: "sync" }));

    expect(screen.getAllByRole("button", { name: "Dismiss notification" })).toHaveLength(2);
  });

  it("throws when used outside a provider rather than silently doing nothing", () => {
    // A mutation that believes it reported success and did not is exactly the
    // dishonesty this kit exists to remove, so this fails loudly.
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    expect(() => render(<Trigger />)).toThrow(/ToastProvider/);
    spy.mockRestore();
  });
});
