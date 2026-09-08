/**
 * Runs before every test file (wired via `test.setupFiles` in vite.config.ts).
 *
 * Registers jest-dom's DOM matchers -- `toBeInTheDocument`, `toHaveClass`,
 * `toBeDisabled` and friends -- on vitest's `expect`, so assertions about
 * rendered output read as assertions about the DOM rather than about strings.
 */
import "@testing-library/jest-dom/vitest";
