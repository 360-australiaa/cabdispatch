import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import NotFound from "./NotFound";

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/live-map" element={<h1>Live map</h1>} />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("NotFound", () => {
  it("says the page was not found rather than redirecting somewhere plausible", () => {
    renderAt("/trps");

    expect(screen.getByRole("heading", { name: "Page not found" })).toBeInTheDocument();
    // The regression this page exists to prevent: the old wildcard rendered
    // the live map here, so a typo looked like a successful navigation.
    expect(screen.queryByRole("heading", { name: "Live map" })).not.toBeInTheDocument();
  });

  it("shows the path that was not found", () => {
    renderAt("/fleet/vehicles/does-not-exist");

    expect(screen.getByText("/fleet/vehicles/does-not-exist")).toBeInTheDocument();
  });

  it("offers a real link back to the live map, not a scripted button", () => {
    renderAt("/nope");

    const link = screen.getByRole("link", { name: "Go to live map" });
    // A real anchor: middle-clickable, copyable, and it shows its target in
    // the status bar like any other link.
    expect(link).toHaveAttribute("href", "/live-map");
  });

  it("does not claim a 404 for a path that does match a route", () => {
    renderAt("/live-map");

    expect(screen.getByRole("heading", { name: "Live map" })).toBeInTheDocument();
    expect(screen.queryByTestId("not-found")).not.toBeInTheDocument();
  });
});
