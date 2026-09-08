import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeToggle } from "@/components/ui/ThemeToggle";
import { THEME_STORAGE_KEY, ThemeProvider, useTheme } from "./theme";

/** Drive `matchMedia` so a test can say what the OS preference is. */
function mockMatchMedia(dark: boolean) {
  const listeners = new Set<(e: MediaQueryListEvent) => void>();
  const mql = {
    matches: dark,
    media: "(prefers-color-scheme: dark)",
    addEventListener: (_: string, cb: (e: MediaQueryListEvent) => void) => listeners.add(cb),
    removeEventListener: (_: string, cb: (e: MediaQueryListEvent) => void) => listeners.delete(cb),
  };
  vi.stubGlobal(
    "matchMedia",
    vi.fn(() => mql),
  );
  return {
    /** Simulate the OS flipping while the app is open. */
    emit(nextDark: boolean) {
      // act(): the listener fires outside React's event system, so without it
      // the resulting setState lands after the assertion.
      act(() => {
        mql.matches = nextDark;
        listeners.forEach((cb) => cb({ matches: nextDark } as MediaQueryListEvent));
      });
    },
  };
}

function Probe() {
  const { preference, resolved } = useTheme();
  return (
    <span data-testid="probe">
      {preference}/{resolved}
    </span>
  );
}

function renderTheme() {
  return render(
    <ThemeProvider>
      <Probe />
      <ThemeToggle />
    </ThemeProvider>,
  );
}

describe("ThemeProvider", () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.documentElement.removeAttribute("data-theme");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("defaults to following the OS", () => {
    mockMatchMedia(true);
    renderTheme();

    expect(screen.getByTestId("probe")).toHaveTextContent("system/dark");
  });

  it("always writes a concrete data-theme, never the word 'system'", () => {
    mockMatchMedia(true);
    renderTheme();

    // The whole mechanism depends on this: because the attribute is always
    // "light" or "dark", one CSS selector (`:root[data-theme="dark"]`) covers
    // every case and Tailwind's `dark:` variant can point at it.
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
  });

  it("lets an explicit choice override the OS -- the thing that was impossible before", async () => {
    mockMatchMedia(true);
    renderTheme();
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");

    await userEvent.click(screen.getByRole("radio", { name: "Light" }));

    // Dark mode used to be a bare `prefers-color-scheme` media query, so a
    // dispatcher on a dark OS could not get a light dashboard at all.
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");
    expect(screen.getByTestId("probe")).toHaveTextContent("light/light");
  });

  it("re-resolves live when the OS flips and the preference is 'system'", () => {
    const media = mockMatchMedia(false);
    renderTheme();
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");

    media.emit(true);

    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
  });

  it("ignores the OS while the preference is explicit, and obeys it again on 'system'", async () => {
    const media = mockMatchMedia(false);
    renderTheme();
    await userEvent.click(screen.getByRole("radio", { name: "Dark" }));

    media.emit(false);
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");

    // Switching back to System must resolve immediately, without a reload --
    // which is why the OS setting is tracked even while it is being ignored.
    await userEvent.click(screen.getByRole("radio", { name: "System" }));
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");
  });

  it("persists the preference and restores it on the next load", async () => {
    mockMatchMedia(false);
    const { unmount } = renderTheme();

    await userEvent.click(screen.getByRole("radio", { name: "Dark" }));
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("dark");

    unmount();
    renderTheme();

    expect(screen.getByTestId("probe")).toHaveTextContent("dark/dark");
  });

  it("falls back to 'system' on a corrupt stored value", () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, "chartreuse");
    mockMatchMedia(true);
    renderTheme();

    expect(screen.getByTestId("probe")).toHaveTextContent("system/dark");
  });

  it("sets color-scheme so native controls and scrollbars follow the theme", () => {
    mockMatchMedia(true);
    renderTheme();

    expect(document.documentElement.style.colorScheme).toBe("dark");
  });

  it("throws outside a provider rather than rendering an untethered theme", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    expect(() => render(<Probe />)).toThrow(/ThemeProvider/);
    spy.mockRestore();
  });
});

describe("ThemeToggle", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("is a radiogroup of three exclusive options with the current one checked", () => {
    mockMatchMedia(false);
    renderTheme();

    expect(screen.getByRole("radiogroup", { name: "Colour theme" })).toBeInTheDocument();
    const radios = screen.getAllByRole("radio");
    expect(radios.map((r) => r.getAttribute("aria-label"))).toEqual(["Light", "Dark", "System"]);
    // Three states, not a two-state switch: "System" is a real choice, and
    // collapsing it into a toggle strands an operator who picked dark at night
    // in dark on the morning shift.
    expect(screen.getByRole("radio", { name: "System" })).toHaveAttribute("aria-checked", "true");
  });

  it("moves the checked state when another option is picked", async () => {
    mockMatchMedia(false);
    renderTheme();

    await userEvent.click(screen.getByRole("radio", { name: "Dark" }));

    expect(screen.getByRole("radio", { name: "Dark" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("radio", { name: "System" })).toHaveAttribute("aria-checked", "false");
  });
});
