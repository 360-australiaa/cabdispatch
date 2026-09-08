import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/lib/theme";
import { Sidebar } from "./Sidebar";

// The sidebar pulls a user, a tenant record and a compliance rollup. None of
// that is what these tests are about -- they are about the nav structure and
// the responsive/collapse behaviour -- so the data sources are stubbed to
// something stable rather than stood up for real.
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

function renderSidebar(props: Parameters<typeof Sidebar>[0] = {}) {
  return render(
    <MemoryRouter>
      <ThemeProvider>
        <Sidebar {...props} />
      </ThemeProvider>
    </MemoryRouter>,
  );
}

describe("Sidebar navigation structure", () => {
  beforeEach(() => window.localStorage.clear());

  it("is a labelled landmark", () => {
    renderSidebar();

    expect(screen.getByRole("complementary", { name: "Main navigation" })).toBeInTheDocument();
  });

  it("groups the modules instead of listing 22 flat items", async () => {
    renderSidebar();

    // Six labelled groups: 22 flat links is past the length a nav list can be
    // scanned, and it gave "Ratings" and "Duress Desk" the same visual weight.
    for (const label of [
      "Operations",
      "Trips & Fares",
      "Fleet",
      "Revenue",
      "Driver Engagement",
      "Administration",
    ]) {
      expect(screen.getByRole("button", { name: label })).toBeInTheDocument();
    }
  });

  it("keeps Getting Started pinned above the groups", () => {
    renderSidebar();

    // Most useful the moment a new tenant logs in, before they have added a
    // vehicle, driver or tariff -- so it does not live inside a group that
    // could be folded away.
    expect(screen.getByRole("link", { name: "Getting Started" })).toBeInTheDocument();
  });

  it("still reaches every module", () => {
    renderSidebar();

    for (const label of ["Live Map", "Dispatch", "Trips", "Billing", "Audit Log", "Ratings"]) {
      expect(screen.getByRole("link", { name: label })).toBeInTheDocument();
    }
  });
});

describe("Sidebar group collapsing", () => {
  beforeEach(() => window.localStorage.clear());

  it("folds a group away and back", async () => {
    renderSidebar();
    const revenue = screen.getByRole("button", { name: "Revenue" });

    expect(revenue).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("link", { name: "Billing" })).toBeInTheDocument();

    await userEvent.click(revenue);

    expect(revenue).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("link", { name: "Billing" })).not.toBeInTheDocument();

    await userEvent.click(revenue);
    expect(screen.getByRole("link", { name: "Billing" })).toBeInTheDocument();
  });

  it("remembers which groups were folded", async () => {
    const { unmount } = renderSidebar();
    await userEvent.click(screen.getByRole("button", { name: "Revenue" }));
    unmount();

    renderSidebar();

    // A dispatcher who never touches Billing should not have to fold it away
    // once per session.
    expect(screen.getByRole("button", { name: "Revenue" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
  });

  it("starts fully expanded when the stored value is corrupt", () => {
    window.localStorage.setItem("cabdispatch.sidebar.collapsedGroups", "{not json");

    renderSidebar();

    expect(screen.getByRole("button", { name: "Revenue" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
  });
});

describe("Sidebar responsive drawer", () => {
  beforeEach(() => window.localStorage.clear());

  it("is slid off-canvas when closed and on-canvas when open", () => {
    const { rerender } = renderSidebar({ mobileOpen: false });
    const aside = screen.getByRole("complementary", { name: "Main navigation" });
    // The app previously had NO mobile behaviour at all: a fixed w-64 h-screen
    // with no breakpoint, so on a phone the nav ate most of the viewport.
    expect(aside).toHaveClass("-translate-x-full");

    rerender(
      <MemoryRouter>
        <ThemeProvider>
          <Sidebar mobileOpen onMobileClose={() => {}} />
        </ThemeProvider>
      </MemoryRouter>,
    );
    expect(screen.getByRole("complementary", { name: "Main navigation" })).toHaveClass(
      "translate-x-0",
    );
  });

  it("closes from the drawer's own close button", async () => {
    const onMobileClose = vi.fn();
    renderSidebar({ mobileOpen: true, onMobileClose });

    await userEvent.click(screen.getByRole("button", { name: "Close navigation" }));

    expect(onMobileClose).toHaveBeenCalled();
  });

  it("closes on Escape", async () => {
    const onMobileClose = vi.fn();
    renderSidebar({ mobileOpen: true, onMobileClose });

    await userEvent.keyboard("{Escape}");

    expect(onMobileClose).toHaveBeenCalled();
  });

  it("does not listen for Escape while the drawer is closed", async () => {
    const onMobileClose = vi.fn();
    renderSidebar({ mobileOpen: false, onMobileClose });

    await userEvent.keyboard("{Escape}");

    expect(onMobileClose).not.toHaveBeenCalled();
  });
});

describe("Sidebar footer", () => {
  beforeEach(() => window.localStorage.clear());

  it("carries the theme toggle alongside the signed-in user", () => {
    renderSidebar();

    const group = screen.getByRole("radiogroup", { name: "Colour theme" });
    expect(within(group).getAllByRole("radio")).toHaveLength(3);
    expect(screen.getByText("Dispatcher")).toBeInTheDocument();
  });
});
