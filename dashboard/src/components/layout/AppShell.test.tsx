import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@/lib/theme";
import { AppShell } from "./AppShell";

/**
 * The error boundary lives inside the shell, around the `<Outlet>` (admin-
 * panel plan §5): a page that throws must leave the sidebar on screen, and
 * picking another page from that sidebar must clear the panel.
 *
 * Same data stubs as `Sidebar.test.tsx` -- the sidebar's user/tenant/
 * compliance sources are not what this test is about.
 */
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ user: { name: "Dispatcher", role: "admin" }, logout: vi.fn() }),
}));
vi.mock("@/lib/platformAdmin", () => ({ isPlatformOwner: () => false }));
vi.mock("@/hooks/useWhite-labelSettings", () => ({
  useTenantQuery: () => ({ data: { name: "Test Fleet", theme_json: {} } }),
}));
vi.mock("@/pages/fleet/api", () => ({
  useComplianceExpiry: () => ({ data: { items: [] } }),
}));
// AppShell now mounts DuressAlertsProvider, so the whole app carries the panic
// alarm rather than just the Duress Desk page (see that provider's doc). Its
// open-events poll is not what this error-boundary test is about; stubbing the
// network call keeps the test hermetic while still exercising the real
// provider, so a future break in its wiring still fails here.
vi.mock("@/pages/duress/api", () => ({
  listDuressEvents: () => Promise.resolve({ items: [], total: 0 }),
}));

function Boom(): never {
  throw new Error("this page exploded");
}

function renderShell(initialPath: string) {
  // retry: false so a stubbed-network failure surfaces immediately rather than
  // being retried past the end of the test.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <ThemeProvider>
          <Routes>
            <Route path="/" element={<AppShell />}>
              <Route path="boom" element={<Boom />} />
              <Route path="live-map" element={<h1>Live map page</h1>} />
            </Route>
          </Routes>
        </ThemeProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("AppShell error boundary", () => {
  let consoleError: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    window.localStorage.clear();
    consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => {
    consoleError.mockRestore();
  });

  it("keeps the sidebar on screen when a page throws", () => {
    renderShell("/boom");

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("this page exploded")).toBeInTheDocument();
    // The whole point of moving the boundary: the nav survives the crash.
    expect(screen.getByRole("complementary", { name: "Main navigation" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Live Map" })).toBeInTheDocument();
  });

  it("clears the panel when the operator navigates away via the sidebar", async () => {
    renderShell("/boom");
    expect(screen.getByRole("alert")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("link", { name: "Live Map" }));

    expect(await screen.findByRole("heading", { name: "Live map page" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("renders a healthy page without a panel", () => {
    renderShell("/live-map");

    expect(screen.getByRole("heading", { name: "Live map page" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
