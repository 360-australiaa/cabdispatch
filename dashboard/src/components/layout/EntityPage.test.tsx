import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { type ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { EntityPage } from "./EntityPage";

/** `EntityPage` reads/writes `?tab=` via `useSearchParams`, so every render
 * needs a router. A `Routes`/`Route` pair (rather than a bare `MemoryRouter`)
 * lets tests assert on the URL the way a real navigation would leave it. */
function renderAt(path: string, element: ReactNode) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/entity" element={element} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("EntityPage", () => {
  it("renders the back link, title, status badge and facts row", () => {
    renderAt(
      "/entity",
      <EntityPage
        kind="driver"
        title="Arsalan Rehman"
        statusBadge={<span>On trip</span>}
        facts={[{ label: "Driver code", value: "D-1042" }]}
        backTo={{ label: "Fleet & Drivers › Drivers", to: "/fleet?tab=drivers" }}
      />,
    );

    expect(screen.getByRole("heading", { level: 1, name: "Arsalan Rehman" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Fleet & Drivers › Drivers/ })).toHaveAttribute(
      "href",
      "/fleet?tab=drivers",
    );
    expect(screen.getByText("On trip")).toBeInTheDocument();
    expect(screen.getByText("Driver code")).toBeInTheDocument();
    expect(screen.getByText("D-1042")).toBeInTheDocument();
  });

  it("renders the actions slot only when given one", () => {
    const { rerender } = render(
      <MemoryRouter initialEntries={["/entity"]}>
        <Routes>
          <Route
            path="/entity"
            element={<EntityPage kind="vehicle" title="T22123" backTo={{ label: "Vehicles", to: "/fleet" }} />}
          />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.queryByRole("button")).not.toBeInTheDocument();

    rerender(
      <MemoryRouter initialEntries={["/entity"]}>
        <Routes>
          <Route
            path="/entity"
            element={
              <EntityPage
                kind="vehicle"
                title="T22123"
                backTo={{ label: "Vehicles", to: "/fleet" }}
                actions={<button type="button">Edit</button>}
              />
            }
          />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
  });

  it("renders a tab bar defaulting to the first tab, and switches on click", async () => {
    renderAt(
      "/entity",
      <EntityPage
        kind="device"
        title="Pixel 7"
        backTo={{ label: "Devices", to: "/fleet?tab=devices" }}
        tabs={[
          { value: "status", label: "Status", content: <p>Status panel</p> },
          { value: "heartbeats", label: "Heartbeats", content: <p>Heartbeats panel</p> },
        ]}
      />,
    );

    expect(screen.getByRole("tab", { selected: true })).toHaveTextContent("Status");
    expect(screen.getByText("Status panel")).toBeInTheDocument();
    expect(screen.queryByText("Heartbeats panel")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("tab", { name: "Heartbeats" }));

    expect(screen.getByText("Heartbeats panel")).toBeInTheDocument();
    expect(screen.queryByText("Status panel")).not.toBeInTheDocument();
  });

  it("honours a requested ?tab= on first render", () => {
    renderAt(
      "/entity?tab=heartbeats",
      <EntityPage
        kind="device"
        title="Pixel 7"
        backTo={{ label: "Devices", to: "/fleet?tab=devices" }}
        tabs={[
          { value: "status", label: "Status", content: <p>Status panel</p> },
          { value: "heartbeats", label: "Heartbeats", content: <p>Heartbeats panel</p> },
        ]}
      />,
    );

    expect(screen.getByRole("tab", { selected: true })).toHaveTextContent("Heartbeats");
    expect(screen.getByText("Heartbeats panel")).toBeInTheDocument();
  });

  it("ignores an invalid ?tab= and falls back to the first tab", () => {
    renderAt(
      "/entity?tab=does-not-exist",
      <EntityPage
        kind="device"
        title="Pixel 7"
        backTo={{ label: "Devices", to: "/fleet?tab=devices" }}
        tabs={[{ value: "status", label: "Status", content: <p>Status panel</p> }]}
      />,
    );

    expect(screen.getByRole("tab", { selected: true })).toHaveTextContent("Status");
  });

  it("shows a loading skeleton instead of tabs/content, and hides it once loaded", () => {
    const { rerender } = render(
      <MemoryRouter initialEntries={["/entity"]}>
        <Routes>
          <Route
            path="/entity"
            element={
              <EntityPage
                kind="trip"
                title="Trip"
                backTo={{ label: "Trips", to: "/trips" }}
                isLoading
                tabs={[{ value: "fare", label: "Fare", content: <p>Fare panel</p> }]}
              />
            }
          />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
    expect(screen.queryByText("Fare panel")).not.toBeInTheDocument();

    rerender(
      <MemoryRouter initialEntries={["/entity"]}>
        <Routes>
          <Route
            path="/entity"
            element={
              <EntityPage
                kind="trip"
                title="Trip"
                backTo={{ label: "Trips", to: "/trips" }}
                tabs={[{ value: "fare", label: "Fare", content: <p>Fare panel</p> }]}
              />
            }
          />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByRole("tablist")).toBeInTheDocument();
    expect(screen.getByText("Fare panel")).toBeInTheDocument();
  });

  it("shows an ErrorBanner instead of tabs/content when given an error", () => {
    renderAt(
      "/entity",
      <EntityPage
        kind="shift"
        title="Shift"
        backTo={{ label: "Shifts", to: "/shifts" }}
        error="Failed to load GET /v1/shifts/abc"
        tabs={[{ value: "summary", label: "Summary", content: <p>Summary panel</p> }]}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("Failed to load GET /v1/shifts/abc");
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
    expect(screen.queryByText("Summary panel")).not.toBeInTheDocument();
  });

  it("renders the right rail only on wide screens, and not at all without one", () => {
    const { container, rerender } = render(
      <MemoryRouter initialEntries={["/entity"]}>
        <Routes>
          <Route
            path="/entity"
            element={<EntityPage kind="vehicle" title="T22123" backTo={{ label: "Vehicles", to: "/fleet" }} />}
          />
        </Routes>
      </MemoryRouter>,
    );
    expect(container.querySelector("[data-entity-kind]")).toBeInTheDocument();
    expect(screen.queryByText("Right rail content")).not.toBeInTheDocument();

    rerender(
      <MemoryRouter initialEntries={["/entity"]}>
        <Routes>
          <Route
            path="/entity"
            element={
              <EntityPage
                kind="vehicle"
                title="T22123"
                backTo={{ label: "Vehicles", to: "/fleet" }}
                rightRail={<p>Right rail content</p>}
              />
            }
          />
        </Routes>
      </MemoryRouter>,
    );
    const rail = screen.getByText("Right rail content");
    expect(rail.closest("div")?.className).toContain("hidden");
    expect(rail.closest("div")?.className).toContain("xl:block");
  });

  it("marks the root with the entity kind for downstream styling/tests", () => {
    renderAt(
      "/entity",
      <EntityPage kind="driver" title="Arsalan Rehman" backTo={{ label: "Drivers", to: "/fleet?tab=drivers" }} />,
    );
    const root = document.querySelector('[data-entity-kind="driver"]');
    expect(root).toBeInTheDocument();
    expect(within(root as HTMLElement).getByText("Arsalan Rehman")).toBeInTheDocument();
  });
});
