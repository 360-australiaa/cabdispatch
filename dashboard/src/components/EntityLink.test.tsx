import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { EntityLink } from "./EntityLink";

describe("EntityLink", () => {
  it.each([
    ["driver", "/drivers/d-1"],
    ["vehicle", "/vehicles/v-1"],
    ["device", "/devices/dev-1"],
    ["trip", "/trips/t-1"],
    ["shift", "/shifts/s-1"],
  ] as const)("links kind=%s to %s", (kind, expectedHref) => {
    const id = expectedHref.split("/")[2];
    render(
      <MemoryRouter>
        <EntityLink kind={kind} id={id} name="Display name" />
      </MemoryRouter>,
    );
    expect(screen.getByRole("link", { name: "Display name" })).toHaveAttribute("href", expectedHref);
  });

  it("navigates to the entity's page when clicked", async () => {
    function Harness() {
      return (
        <Routes>
          <Route path="/" element={<EntityLink kind="driver" id="d-42" name="Arsalan Rehman" />} />
          <Route path="/drivers/d-42" element={<h1>Driver page</h1>} />
        </Routes>
      );
    }
    render(
      <MemoryRouter initialEntries={["/"]}>
        <Harness />
      </MemoryRouter>,
    );

    await userEvent.click(screen.getByRole("link", { name: "Arsalan Rehman" }));

    expect(screen.getByRole("heading", { name: "Driver page" })).toBeInTheDocument();
  });

  it("stops the click from bubbling to an enclosing row/field handler", async () => {
    const onOuterClick = vi.fn();
    render(
      <MemoryRouter>
        {/* eslint-disable-next-line jsx-a11y/no-static-element-interactions, jsx-a11y/click-events-have-key-events */}
        <div onClick={onOuterClick}>
          <EntityLink kind="vehicle" id="v-1" name="T22123" />
        </div>
      </MemoryRouter>,
    );

    await userEvent.click(screen.getByRole("link", { name: "T22123" }));

    expect(onOuterClick).not.toHaveBeenCalled();
  });

  it("renders as an ordinary link (underline on hover only), not a coloured button", () => {
    render(
      <MemoryRouter>
        <EntityLink kind="vehicle" id="v-1" name="T22123" />
      </MemoryRouter>,
    );
    const link = screen.getByRole("link", { name: "T22123" });
    const classes = link.className.split(/\s+/);
    expect(classes).toContain("hover:underline");
    // No bare `underline` utility -- the link is not underlined at rest.
    expect(classes).not.toContain("underline");
  });
});
