import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "react-router-dom";
import { AuthProvider } from "@/lib/auth";
import { ThemeProvider } from "@/lib/theme";
import { I18nProvider } from "@/lib/i18n";
import { router } from "@/router";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      /**
       * MUST stay `true` — `lib/pollIntervals.ts` depends on it.
       *
       * Field report, 2026-09-18 ("my dashboard is not real time, I have to
       * reload everything"): this was `false`, and every polling site sets
       * `refetchIntervalInBackground: false`. The two together meant a tab
       * that lost focus stopped polling AND never caught up on return — it
       * sat on whatever it last had until the next interval tick, or forever
       * on the screens that only fetch once. Reproduced live: a backgrounded
       * Live Map made zero `/v1/*` requests over two minutes while still
       * showing a green "Live" badge.
       *
       * Pausing polls in a hidden tab is deliberate and worth keeping (see
       * that module's header for the load argument). It is only safe while
       * focus refetching puts the data right the instant the operator looks
       * again, which is exactly what this line does.
       */
      refetchOnWindowFocus: true,
    },
  },
});

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      {/* Outside AuthProvider: the theme applies to the login screen too, and
          survives a logout. */}
      <ThemeProvider>
        <I18nProvider>
          <AuthProvider>
            <RouterProvider router={router} />
          </AuthProvider>
        </I18nProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
