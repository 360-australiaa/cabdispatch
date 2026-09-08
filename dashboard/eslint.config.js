import js from "@eslint/js";
import globals from "globals";
import tseslint from "typescript-eslint";
import reactHooks from "eslint-plugin-react-hooks";
import jsxA11y from "eslint-plugin-jsx-a11y";

/**
 * ESLint 9 flat config for the dashboard.
 *
 * This codebase reached ~160 files and 22 wired modules with NO linter at all
 * -- `npm run lint` was `tsc --noEmit` and nothing else (dashboard audit §6).
 * So this config's job on day one is to be a real, running baseline that CI
 * can gate on, not to relitigate every line written before it existed.
 *
 * That shapes two deliberate decisions:
 *
 * 1. The rules that fire in bulk on the existing tree are `warn`, not `error`
 *    -- above all `react-hooks/exhaustive-deps`, which the audit counted 22
 *    suppressions of, all on the same "reset the form when the target prop
 *    changes" pattern in every form modal. Those are real findings and they
 *    should be read and fixed, one workstream at a time, by whoever owns each
 *    file. Making them errors today would either force ~160 files of edits in
 *    one commit (guaranteeing conflicts with every other agent working in
 *    parallel) or force a second wave of blanket suppressions, which is worse
 *    than the warning.
 * 2. What IS an error is what a linter catches that a compiler does not and
 *    that is nearly always a genuine bug: the rules-of-hooks violations, and
 *    `no-empty` / `no-constant-condition`-class mistakes from `js.configs`.
 *
 * Type checking stays the tsc pass in `npm run lint` (`eslint . && tsc
 * --noEmit`), so nothing here needs type-aware linting and this config runs
 * fast.
 */
export default tseslint.config(
  {
    // Build output, dependencies, and the coverage dir vitest may write.
    ignores: ["dist", "coverage", "node_modules"],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["**/*.{ts,tsx}"],
    languageOptions: {
      ecmaVersion: 2022,
      globals: {
        ...globals.browser,
        ...globals.es2021,
      },
    },
    plugins: {
      "react-hooks": reactHooks,
      "jsx-a11y": jsxA11y,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      ...jsxA11y.flatConfigs.recommended.rules,

      // A hook called conditionally or in a loop is a real bug, always. This
      // is the one react-hooks rule that never needs a judgement call, so it
      // is the one that blocks.
      "react-hooks/rules-of-hooks": "error",

      // See decision (1) above. 22 existing suppressions, one recurring
      // pattern, and a genuine bug behind at least one of them
      // (billing/index.tsx's useMemo-as-effect, audit §6) -- all worth
      // reading, none worth a 160-file commit today.
      "react-hooks/exhaustive-deps": "warn",

      // react-hooks 7 ships the React Compiler's own analyses as lint rules,
      // and they default to `error`. On this tree that is 43 findings across
      // dozens of files, ~36 of them the single "reset the form state when
      // the modal's target prop changes" pattern that every form modal here
      // uses -- the same pattern the exhaustive-deps suppressions were
      // already covering. They are worth fixing (`useEffect` + `setState` on
      // an `open` prop really should be a `key` on the modal instead), and
      // fixing them is a real refactor of every form modal, not a lint pass.
      // Warn now, so the count is visible and can be driven down file by
      // file; do not block every unrelated change on it in the meantime.
      "react-hooks/set-state-in-effect": "warn",
      "react-hooks/set-state-in-render": "warn",
      "react-hooks/refs": "warn",
      "react-hooks/purity": "warn",

      // Accessibility: 17 of 97 .tsx files use any aria-* attribute at all,
      // Modal has no focus trap, and Table's sort headers are mouse-only
      // (audit §6). Every one of those is a finding this plugin will report.
      // They are warnings so the whole a11y backlog surfaces in one place and
      // can be worked down deliberately, rather than blocking every unrelated
      // change until it is finished.
      "jsx-a11y/no-autofocus": "warn",
      "jsx-a11y/click-events-have-key-events": "warn",
      "jsx-a11y/no-static-element-interactions": "warn",
      "jsx-a11y/no-noninteractive-element-interactions": "warn",
      "jsx-a11y/label-has-associated-control": "warn",
      "jsx-a11y/anchor-is-valid": "warn",
      "jsx-a11y/heading-has-content": "warn",

      // `interface ButtonProps extends ButtonHTMLAttributes<...>,
      // VariantProps<typeof buttonVariants> {}` -- an empty body that exists
      // purely to name the intersection of two prop types. That is the
      // established kit idiom here, and a type alias would read worse.
      "@typescript-eslint/no-empty-object-type": "warn",

      // The repo already holds itself to zero `any` (audit §6 confirms zero),
      // so this stays on as an error -- it is a standard the code currently
      // meets, and the point of a linter is to keep it that way.
      "@typescript-eslint/no-explicit-any": "error",

      // Unused code is worth flagging but is never a correctness bug, and the
      // leading-underscore escape hatch is the repo's existing convention for
      // deliberately-ignored args (see `_data` in the fleet api mutations).
      "@typescript-eslint/no-unused-vars": [
        "warn",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_", caughtErrors: "none" },
      ],

      // A bare `catch {}` is load-bearing in several places here and says so
      // in a comment each time (useForceUpdateAll's per-device best-effort
      // loop, for one). Empty blocks elsewhere still warn.
      "no-empty": ["warn", { allowEmptyCatch: true }],
    },
  },
  {
    // Config files and the test setup run in Node, not the browser.
    files: ["*.config.{js,ts}", "src/setupTests.ts"],
    languageOptions: {
      globals: { ...globals.node },
    },
  },
  {
    // Vitest injects describe/it/expect as globals (see vite.config.ts's
    // `test.globals`), so tests need them declared here rather than imported.
    files: ["**/*.test.{ts,tsx}"],
    languageOptions: {
      globals: { ...globals.node, ...globals.vitest },
    },
  },
);
