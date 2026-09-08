import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { Car, Users } from "lucide-react";
import { Tabs, TabPanel, type TabItem } from "./Tabs";

type Key = "vehicles" | "drivers" | "devices";

const ITEMS: TabItem<Key>[] = [
  { value: "vehicles", label: "Vehicles", icon: Car },
  { value: "drivers", label: "Drivers", icon: Users },
  { value: "devices", label: "Devices" },
];

/** A controlled harness, matching how every migrated page drives Tabs. */
function Harness({ items = ITEMS, initial = "vehicles" as Key }) {
  const [tab, setTab] = useState<Key>(initial);
  return (
    <>
      <Tabs items={items} value={tab} onChange={setTab} label="Fleet sections" />
      <TabPanel value={tab}>Panel: {tab}</TabPanel>
    </>
  );
}

describe("Tabs semantics", () => {
  it("renders a labelled tablist of tabs, one selected", () => {
    render(<Harness />);

    const list = screen.getByRole("tablist", { name: "Fleet sections" });
    expect(list).toBeInTheDocument();
    const tabs = screen.getAllByRole("tab");
    expect(tabs.map((t) => t.textContent)).toEqual(["Vehicles", "Drivers", "Devices"]);
    expect(screen.getByRole("tab", { selected: true })).toHaveTextContent("Vehicles");
  });

  it("ties each tab to its panel", () => {
    render(<Harness />);

    const selected = screen.getByRole("tab", { selected: true });
    const panel = screen.getByRole("tabpanel");
    // The pair of references is what lets a screen reader move from the tab to
    // the content it controls; none of the ten hand-rolled bars had either.
    expect(selected).toHaveAttribute("aria-controls", panel.id);
    expect(panel).toHaveAttribute("aria-labelledby", selected.id);
  });

  it("puts only the selected tab in the tab order (roving tabIndex)", async () => {
    render(<Harness />);

    const [vehicles, drivers, devices] = screen.getAllByRole("tab");
    expect(vehicles).toHaveAttribute("tabindex", "0");
    expect(drivers).toHaveAttribute("tabindex", "-1");
    expect(devices).toHaveAttribute("tabindex", "-1");

    // Tab from the bar lands in the panel, not on the second tab -- the whole
    // point of a roving tabIndex.
    await userEvent.click(vehicles);
    await userEvent.tab();
    expect(screen.getByRole("tabpanel")).toHaveFocus();
  });
});

describe("Tabs interaction", () => {
  it("selects on click and swaps the panel", async () => {
    render(<Harness />);

    await userEvent.click(screen.getByRole("tab", { name: "Drivers" }));

    expect(screen.getByRole("tab", { selected: true })).toHaveTextContent("Drivers");
    expect(screen.getByRole("tabpanel")).toHaveTextContent("Panel: drivers");
  });

  it("moves and selects with Left/Right arrows, wrapping at both ends", async () => {
    render(<Harness />);
    screen.getByRole("tab", { name: "Vehicles" }).focus();

    await userEvent.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { selected: true })).toHaveTextContent("Drivers");

    await userEvent.keyboard("{ArrowRight}");
    await userEvent.keyboard("{ArrowRight}");
    // Past the end wraps back to the first tab rather than dead-ending.
    expect(screen.getByRole("tab", { selected: true })).toHaveTextContent("Vehicles");

    await userEvent.keyboard("{ArrowLeft}");
    expect(screen.getByRole("tab", { selected: true })).toHaveTextContent("Devices");
  });

  it("jumps to the first and last tab with Home and End", async () => {
    render(<Harness initial="drivers" />);
    screen.getByRole("tab", { name: "Drivers" }).focus();

    await userEvent.keyboard("{End}");
    expect(screen.getByRole("tab", { selected: true })).toHaveTextContent("Devices");

    await userEvent.keyboard("{Home}");
    expect(screen.getByRole("tab", { selected: true })).toHaveTextContent("Vehicles");
  });

  it("moves focus with the selection, so the keyboard is not stranded", async () => {
    render(<Harness />);
    screen.getByRole("tab", { name: "Vehicles" }).focus();

    await userEvent.keyboard("{ArrowRight}");

    // The previously-selected tab has just become tabIndex=-1; if focus did
    // not follow, the next arrow press would go nowhere.
    expect(screen.getByRole("tab", { name: "Drivers" })).toHaveFocus();
  });

  it("skips a disabled tab when arrowing and refuses to select it", async () => {
    const items: TabItem<Key>[] = [
      { value: "vehicles", label: "Vehicles" },
      { value: "drivers", label: "Drivers", disabled: true },
      { value: "devices", label: "Devices" },
    ];
    render(<Harness items={items} />);
    screen.getByRole("tab", { name: "Vehicles" }).focus();

    await userEvent.keyboard("{ArrowRight}");

    expect(screen.getByRole("tab", { selected: true })).toHaveTextContent("Devices");
    expect(screen.getByRole("tab", { name: "Drivers" })).toBeDisabled();
  });
});

describe("Tabs variants", () => {
  it("renders both the underline and pill styles from one component", () => {
    // The two styles are the two that already existed in the pages (four bars
    // underline, six pill). Keeping both is what makes the migration a pure
    // consolidation with no page changing visually.
    const { rerender, container } = render(
      <Tabs items={ITEMS} value="vehicles" onChange={() => {}} variant="underline" />,
    );
    expect(container.querySelector('[role="tablist"]')).toHaveClass("border-b");

    rerender(<Tabs items={ITEMS} value="vehicles" onChange={() => {}} variant="pill" />);
    expect(container.querySelector('[role="tablist"]')).toHaveClass("bg-muted");
  });

  it("renders a badge beside a tab label when given one", () => {
    const items: TabItem<Key>[] = [
      { value: "vehicles", label: "Vehicles", badge: <span>3</span> },
      { value: "drivers", label: "Drivers" },
      { value: "devices", label: "Devices" },
    ];
    render(<Tabs items={items} value="vehicles" onChange={() => {}} />);

    expect(screen.getByRole("tab", { name: /Vehicles/ })).toHaveTextContent("3");
  });
});
