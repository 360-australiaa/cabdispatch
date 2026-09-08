import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Table, type TableColumn } from "./Table";

interface Row {
  id: string;
  driver: string;
  fare: number;
  closed: string | null;
}

const ROWS: Row[] = [
  { id: "3", driver: "Carol", fare: 42.5, closed: "2026-09-01T10:00:00Z" },
  { id: "1", driver: "alice", fare: 7.25, closed: null },
  { id: "2", driver: "Bob", fare: 19, closed: "2026-08-30T10:00:00Z" },
];

const COLUMNS: TableColumn<Row>[] = [
  { key: "driver", header: "Driver", sortable: true },
  { key: "fare", header: "Fare", sortable: true, render: (r) => `$${r.fare.toFixed(2)}` },
  { key: "closed", header: "Closed", sortable: true },
  { key: "id", header: "ID" },
];

/** The rendered body rows, as arrays of cell text -- the thing sorting and paging actually change. */
function bodyRows(): string[][] {
  const rows = screen.getAllByRole("row").slice(1); // drop the header row
  return rows.map((r) => within(r).getAllByRole("cell").map((c) => c.textContent ?? ""));
}

function firstColumn(): string[] {
  return bodyRows().map((cells) => cells[0]);
}

describe("Table rendering", () => {
  it("renders a cell per column, using `render` where given and row[key] otherwise", () => {
    render(<Table columns={COLUMNS} data={[ROWS[0]]} rowKey={(r) => r.id} />);

    expect(bodyRows()).toEqual([["Carol", "$42.50", "2026-09-01T10:00:00Z", "3"]]);
  });

  it("renders a null/undefined cell as empty rather than the string 'null'", () => {
    render(<Table columns={COLUMNS} data={[ROWS[1]]} rowKey={(r) => r.id} />);

    expect(bodyRows()[0][2]).toBe("");
  });

  it("shows the loading state instead of rows", () => {
    render(<Table columns={COLUMNS} data={ROWS} rowKey={(r) => r.id} isLoading />);

    expect(screen.getByText("Loading…")).toBeInTheDocument();
    expect(screen.queryByText("Carol")).not.toBeInTheDocument();
  });

  it("shows a default empty state, or the caller's", () => {
    const { rerender } = render(<Table columns={COLUMNS} data={[]} rowKey={(r) => r.id} />);
    expect(screen.getByText("No data")).toBeInTheDocument();

    rerender(
      <Table columns={COLUMNS} data={[]} rowKey={(r) => r.id} emptyState="No trips today" />,
    );
    expect(screen.getByText("No trips today")).toBeInTheDocument();
  });

  it("calls onRowClick with the row that was clicked", async () => {
    const onRowClick = vi.fn();
    render(<Table columns={COLUMNS} data={ROWS} rowKey={(r) => r.id} onRowClick={onRowClick} />);

    await userEvent.click(screen.getByText("Bob"));

    expect(onRowClick).toHaveBeenCalledWith(ROWS[2]);
  });
});

describe("Table client-side sorting", () => {
  it("leaves the data in source order until a header is clicked", () => {
    render(<Table columns={COLUMNS} data={ROWS} rowKey={(r) => r.id} />);
    expect(firstColumn()).toEqual(["Carol", "alice", "Bob"]);
  });

  it("cycles a sortable header asc -> desc -> unsorted", async () => {
    render(<Table columns={COLUMNS} data={ROWS} rowKey={(r) => r.id} />);
    const fare = screen.getByText("Fare");

    await userEvent.click(fare);
    expect(bodyRows().map((c) => c[1])).toEqual(["$7.25", "$19.00", "$42.50"]);

    await userEvent.click(fare);
    expect(bodyRows().map((c) => c[1])).toEqual(["$42.50", "$19.00", "$7.25"]);

    await userEvent.click(fare);
    // Third click clears the sort and restores the original order.
    expect(firstColumn()).toEqual(["Carol", "alice", "Bob"]);
  });

  it("starts a new column at ascending when the sort moves", async () => {
    render(<Table columns={COLUMNS} data={ROWS} rowKey={(r) => r.id} />);

    await userEvent.click(screen.getByText("Fare"));
    await userEvent.click(screen.getByText("Fare")); // now descending
    await userEvent.click(screen.getByText("Driver"));

    // Raw `<` on strings, so this is codepoint order: uppercase before
    // lowercase, which is why "alice" sorts last rather than first. Ops-
    // visible quirk, recorded here so a future locale-aware compare is a
    // deliberate change and not an accident.
    expect(firstColumn()).toEqual(["Bob", "Carol", "alice"]);
  });

  it("sorts nulls first ascending, regardless of the rest of the order", async () => {
    render(<Table columns={COLUMNS} data={ROWS} rowKey={(r) => r.id} />);

    await userEvent.click(screen.getByText("Closed"));

    expect(firstColumn()).toEqual(["alice", "Bob", "Carol"]);
  });

  it("uses sortAccessor when the display value is not the sort value", async () => {
    const columns: TableColumn<Row>[] = [
      {
        key: "driver",
        header: "Driver",
        sortable: true,
        // Case-insensitive, which the default accessor is not.
        sortAccessor: (r) => r.driver.toLowerCase(),
      },
    ];
    render(<Table columns={columns} data={ROWS} rowKey={(r) => r.id} />);

    await userEvent.click(screen.getByText("Driver"));

    expect(firstColumn()).toEqual(["alice", "Bob", "Carol"]);
  });

  it("ignores clicks on a column that is not sortable", async () => {
    render(<Table columns={COLUMNS} data={ROWS} rowKey={(r) => r.id} />);

    await userEvent.click(screen.getByText("ID"));

    expect(firstColumn()).toEqual(["Carol", "alice", "Bob"]);
  });
});

describe("Table pagination", () => {
  const many: Row[] = Array.from({ length: 7 }, (_, i) => ({
    id: String(i),
    driver: `Driver ${i}`,
    fare: i,
    closed: null,
  }));

  it("shows no pager when pageSize is omitted", () => {
    render(<Table columns={COLUMNS} data={many} rowKey={(r) => r.id} />);

    expect(screen.queryByRole("button", { name: "Next" })).not.toBeInTheDocument();
    expect(bodyRows()).toHaveLength(7);
  });

  it("shows no pager when everything fits on one page", () => {
    render(<Table columns={COLUMNS} data={many.slice(0, 3)} rowKey={(r) => r.id} pageSize={3} />);

    expect(screen.queryByRole("button", { name: "Next" })).not.toBeInTheDocument();
  });

  it("slices the data and walks pages, with a correct final short page", async () => {
    render(<Table columns={COLUMNS} data={many} rowKey={(r) => r.id} pageSize={3} />);

    expect(firstColumn()).toEqual(["Driver 0", "Driver 1", "Driver 2"]);
    expect(screen.getByText("Page 1 of 3")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();

    await userEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(firstColumn()).toEqual(["Driver 3", "Driver 4", "Driver 5"]);

    await userEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(firstColumn()).toEqual(["Driver 6"]);
    expect(screen.getByText("Page 3 of 3")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();

    await userEvent.click(screen.getByRole("button", { name: "Previous" }));
    expect(firstColumn()).toEqual(["Driver 3", "Driver 4", "Driver 5"]);
  });

  it("paginates the sorted order, not the source order", async () => {
    render(<Table columns={COLUMNS} data={many} rowKey={(r) => r.id} pageSize={3} />);

    await userEvent.click(screen.getByText("Fare"));
    await userEvent.click(screen.getByText("Fare")); // descending

    expect(firstColumn()).toEqual(["Driver 6", "Driver 5", "Driver 4"]);
  });

  /**
   * KNOWN DEFECT, asserted as-is rather than as it should be.
   *
   * `page` is never reset when `data` changes, so a caller who filters a list
   * while the user is on page 3 leaves the table showing an out-of-range
   * slice: an empty body under a "Page 3 of 1" pager, with Previous enabled
   * and Next disabled. Every page in the dashboard that pairs a filter input
   * with a paginated Table can reproduce it.
   *
   * The fix (reset `page` to 0 when `data` identity or the sort changes) is a
   * change to Table.tsx, which this workstream does not own -- D4 owns the
   * design system. So this test locks in the current behaviour and the bug is
   * reported instead. Whoever fixes it will see this test fail, which is
   * exactly the signal they want.
   */
  it("does NOT reset to page 1 when the data shrinks (known defect)", async () => {
    const { rerender } = render(
      <Table columns={COLUMNS} data={many} rowKey={(r) => r.id} pageSize={3} />,
    );
    await userEvent.click(screen.getByRole("button", { name: "Next" }));
    await userEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(screen.getByText("Page 3 of 3")).toBeInTheDocument();

    // A filter is applied and only two rows survive.
    rerender(
      <Table columns={COLUMNS} data={many.slice(0, 2)} rowKey={(r) => r.id} pageSize={3} />,
    );

    // What the operator sees: an empty table, and a pager that contradicts
    // itself. The correct behaviour would be page 1 showing both rows.
    expect(screen.getByText("No data")).toBeInTheDocument();
    expect(screen.queryByText("Page 1 of 1")).not.toBeInTheDocument();
  });
});
