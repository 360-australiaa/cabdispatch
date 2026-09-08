import { createContext, useContext, useMemo, type ReactNode, createElement } from "react";
import { EN_AU, type I18nKey } from "./dictionary";

/**
 * The dashboard's i18n seam. See `dictionary.ts`'s module doc for what is and
 * is not wired through this yet.
 *
 * Deliberately not a library (no `react-i18next`/`FormatJS`): one locale,
 * one flat dictionary, and a handful of `{placeholder}` substitutions is the
 * entire requirement today, and D5's `vite.config.ts` note (see the "WAVE 3
 * NOTE" comment there) is explicit that naming an i18n package in
 * `manualChunks` would pull it into the static first-paint import graph.
 * Pulling in a real i18n runtime is a decision for whoever adds the second
 * locale, made with real bundle numbers in front of them — not a default to
 * reach for now.
 */

type Substitutions = Record<string, string | number>;

function interpolate(template: string, subs?: Substitutions): string {
  if (!subs) return template;
  return template.replace(/\{(\w+)\}/g, (match, key: string) =>
    key in subs ? String(subs[key]) : match,
  );
}

/** Renders a missing key visibly rather than silently — see dictionary.ts. */
function missing(key: string): string {
  return `⟦missing:${key}⟧`;
}

export function translate(key: I18nKey, subs?: Substitutions): string {
  const template = EN_AU[key];
  if (template == null) return missing(key);
  return interpolate(template, subs);
}

interface I18nContextValue {
  locale: "en-AU";
  t: (key: I18nKey, subs?: Substitutions) => string;
}

const I18nContext = createContext<I18nContextValue | undefined>(undefined);

export function I18nProvider({ children }: { children: ReactNode }) {
  const value = useMemo<I18nContextValue>(
    () => ({ locale: "en-AU", t: (key, subs) => translate(key, subs) }),
    [],
  );
  return createElement(I18nContext.Provider, { value }, children);
}

/**
 * `t()` also works outside a provider (falls back to the same `en-AU`
 * dictionary) so plain utility modules — not just components — can call it
 * without threading context through; the provider only exists so a future
 * second locale has one place to switch the active dictionary.
 */
export function useI18n(): I18nContextValue {
  const ctx = useContext(I18nContext);
  return ctx ?? { locale: "en-AU", t: (key, subs) => translate(key, subs) };
}

export { EN_AU } from "./dictionary";
export type { I18nKey } from "./dictionary";
export * from "./jurisdiction";
