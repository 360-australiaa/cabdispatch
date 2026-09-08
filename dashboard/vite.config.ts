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
  },
});
