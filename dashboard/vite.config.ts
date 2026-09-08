/// <reference types="vitest" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 5173,
  },
  build: {
    rollupOptions: {
      output: {
        /**
         * Split the heavy third-party libraries into their own chunks.
         *
         * `router.tsx` code-splits the 22 pages (`React.lazy`), which alone
         * would leave each page's chunk inlining whatever big library it
         * happens to touch — mapbox-gl would end up duplicated across Live
         * Map and the two map pickers, recharts across every page with a
         * graph. Naming them here means one copy each, downloaded once and
         * cached across navigations, instead of the same megabyte arriving
         * again under a different chunk name.
         *
         * Only genuinely large, stable dependencies are named. Everything
         * else is left to Rollup, which is better at this than a hand-written
         * list that goes stale the first time someone adds a package.
         */
        manualChunks: {
          // ~1.9 MB on its own, and only the map pages need it. Naming it
          // keeps the two map surfaces sharing one copy instead of inlining
          // it into each page chunk.
          mapbox: ["mapbox-gl"],
          // The framework itself: changes rarely, so a long-lived cache entry
          // that survives every app deploy.
          react: ["react", "react-dom", "react-router-dom"],
          // Data layer + HTTP, needed by every authenticated page.
          data: ["@tanstack/react-query", "axios"],

          // DELIBERATELY NOT LISTED: `recharts`.
          //
          // Naming a package here does not merely group it — it makes the
          // chunk a *static* import of the entry, so it is fetched and
          // module-preloaded on first paint. Adding `charts: ["recharts"]`
          // measurably made things worse: it pulled 383 kB into the initial
          // load for a library only Compliance's revenue section and Duress's
          // GPS trace ever touch. Left alone, Rollup sees recharts is
          // reachable only through `React.lazy` and gives it a lazy 372 kB
          // chunk that nobody downloads until they open one of those two
          // pages. Verified by building it both ways.
          //
          // mapbox-gl does not have this problem because nothing outside the
          // lazily-loaded map pages imports it either — it stays out of the
          // entry's static graph and is only listed above so the several map
          // pages share one copy.
        },
      },
    },
  },
  // Vitest reads this file, so tests get the same `@` alias and the same React
  // plugin the app is built with -- one config, no chance of the test
  // environment resolving an import differently from production.
  test: {
    // Components under test render real DOM (see Button.test.tsx), so they
    // need a document; node's default environment has none.
    environment: "jsdom",
    // describe/it/expect without an import in every file, matching the
    // convention the eslint config's test-file globals block assumes.
    globals: true,
    setupFiles: ["./src/setupTests.ts"],
    css: false,
    // `e2e/` holds Playwright specs, which import from `@playwright/test` and
    // drive a real browser against a real backend. Vitest would otherwise
    // collect them by filename and fail on the first Playwright-only API.
    // Two runners, two directories: `src/**/*.test.*` is vitest,
    // `e2e/**/*.spec.ts` is Playwright.
    exclude: ["**/node_modules/**", "**/dist/**", "e2e/**"],
  },
});
