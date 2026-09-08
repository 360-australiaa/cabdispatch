import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

/** What the operator chose. `system` follows the OS and keeps following it. */
export type ThemePreference = "light" | "dark" | "system";
/** What is actually on screen -- `system` resolved against the OS. */
export type ResolvedTheme = "light" | "dark";

export const THEME_STORAGE_KEY = "cabdispatch.theme";

interface ThemeContextValue {
  preference: ThemePreference;
  resolved: ResolvedTheme;
  setPreference: (preference: ThemePreference) => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

/**
 * Dark mode used to be broken in two directions at once, and this module picks
 * one mechanism to fix both.
 *
 * What was wrong: `tailwind.config.js` declared `darkMode: ["class"]`, but
 * nothing anywhere added a `.dark` class, so every `dark:` utility in the
 * codebase silently rendered its light value -- dead code that looked like
 * working code. Separately, the only real dark styling was a
 * `prefers-color-scheme` media query, so dark mode was purely OS-driven with
 * no way for an operator to override it (a dispatcher on a bright OS theme in
 * a night cab could not darken the screen, and vice versa).
 *
 * The mechanism: **`data-theme` on `<html>`, always present and always
 * resolved.** The provider writes `data-theme="light"` or `data-theme="dark"`
 * -- never the literal string `system`, which is a *preference*, not a theme.
 * Because the attribute is always one of the two concrete values, one CSS
 * selector (`:root[data-theme="dark"]`) covers every case, and Tailwind's
 * `dark:` variant is pointed at that same selector in `tailwind.config.js`.
 * So there is exactly one source of truth instead of a dead class hook and a
 * media query that disagreed with each other.
 *
 * `system` still works: the preference is stored as `system`, and an OS change
 * re-resolves it live through the `matchMedia` listener below.
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [preference, setPreferenceState] = useState<ThemePreference>(readStoredPreference);
  const [systemDark, setSystemDark] = useState(prefersDark);

  // Track the OS setting even while the preference is explicit, so switching
  // back to "system" resolves correctly without a reload.
  useEffect(() => {
    if (typeof window === "undefined" || !window.matchMedia) return;
    const query = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = (e: MediaQueryListEvent) => setSystemDark(e.matches);
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);

  const resolved: ResolvedTheme =
    preference === "system" ? (systemDark ? "dark" : "light") : preference;

  useEffect(() => {
    document.documentElement.setAttribute("data-theme", resolved);
    // Tells the browser which scrollbars, form controls and default canvas to
    // paint. Without it the native widgets stay light on a dark page.
    document.documentElement.style.colorScheme = resolved;
  }, [resolved]);

  const setPreference = useCallback((next: ThemePreference) => {
    setPreferenceState(next);
    try {
      window.localStorage.setItem(THEME_STORAGE_KEY, next);
    } catch {
      // Private browsing or a locked-down profile: the choice still applies to
      // this session, it just will not survive a reload. Not worth failing on.
    }
  }, []);

  const value = useMemo(
    () => ({ preference, resolved, setPreference }),
    [preference, resolved, setPreference],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

function prefersDark(): boolean {
  if (typeof window === "undefined" || !window.matchMedia) return false;
  return window.matchMedia("(prefers-color-scheme: dark)").matches;
}

function readStoredPreference(): ThemePreference {
  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    if (stored === "light" || stored === "dark" || stored === "system") return stored;
  } catch {
    // Ignore -- fall through to the default.
  }
  return "system";
}

/**
 * Read/!set the theme. Returns a working no-op-free default outside a provider
 * so a component rendered in isolation (a test, a Storybook-style harness)
 * does not explode; unlike `useToast`, nothing here can silently lose data.
 */
export function useTheme(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) {
    throw new Error("useTheme must be used within a <ThemeProvider>");
  }
  return ctx;
}
