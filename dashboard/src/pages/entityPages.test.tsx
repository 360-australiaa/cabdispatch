import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import TripPage from "./trips/TripPage";
import ShiftPage from "./shifts/ShiftPage";

/**
 * Route-level smoke tests for F1's entity-page shells that are still
 * placeholders. Each page here is a placeholder `EntityPage` for now (see
 * the page files' own docs) -- these tests are the "does the route + shell
 * compile and render for a real id" check the plan asks for, not a check of
 * real entity content (there is none yet).
 *
 * `/drivers/:driverId`, `/devices/:deviceId` and `/vehicles/:vehicleId`
 * graduated out of this file when their own workstreams (§4, §6, §5)
 * replaced their placeholders with the real page -- each now has its own
 * `<Page>.test.tsx` with the `QueryClientProvider`/`useAuth`/MSW wiring real
 * data requires, which this shared placeholder-only smoke test never sets
 * up (rendering a real page here would throw with no query client in
 * context, not skip a still-relevant check). Trip and Shift stay here until
 * their own workstreams do the same.
 */
describe.each([
  { path: "/trips/:tripId", at: "/trips/t-100", Page: TripPage, kind: "trip", title: "Trip t-100" },
  { path: "/shifts/:shiftId", at: "/shifts/s-3", Page: ShiftPage, kind: "shift", title: "Shift s-3" },
])("$at", ({ path, at, Page, kind, title }) => {
  it(`renders the EntityPage shell with kind=${kind} and the id in the title`, () => {
    render(
      <MemoryRouter initialEntries={[at]}>
        <Routes>
          <Route path={path} element={<Page />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByRole("heading", { level: 1, name: title })).toBeInTheDocument();
    expect(document.querySelector(`[data-entity-kind="${kind}"]`)).toBeInTheDocument();
    expect(screen.getByText(/Coming soon/)).toBeInTheDocument();
  });
});
