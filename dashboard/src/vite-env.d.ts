/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL?: string;
  /** Mapbox GL JS public token (pk.*). Live Map falls back to a plain canvas plot if unset. */
  readonly VITE_MAPBOX_TOKEN?: string;
  /** The literal string "true" enables test-only tooling in the UI (see
   * .env.example). Any other value, including unset, hides it entirely. Never
   * set in a deployment carrying real data. */
  readonly VITE_ENABLE_TEST_TOOLING?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
