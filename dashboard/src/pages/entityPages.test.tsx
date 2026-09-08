import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import DriverPage from "./drivers/DriverPage";
import VehiclePage from "./vehicles/VehiclePage";
import DevicePage from "./devices/DevicePage";
import TripPage from "./trips/TripPage";
import ShiftPage from "./shifts/ShiftPage";

/**
 * Route-level smoke tests for F1's five entity-page shells. Each page is a
 * placeholder `EntityPage` for now (see the page files' own docs) -- these
 * tests are the "does the route + shell compile and render for a real id"
 * check the plan asks for, not a check of real entity content (there is
 * none yet).
 */
describe.each([
  { path: "/drivers/:driverId", at: "/drivers/d-42", Page: DriverPage, kind: "driver", title: "Driver d-42" },
  { path: "/vehicles/:vehicleId", at: "/vehicles/v-7", Page: VehiclePage, kind: "vehicle", title: "Vehicle v-7" },
  { path: "/devices/:deviceId", at: "/devices/dev-9", Page: DevicePage, kind: "device", title: "Device dev-9" },
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
