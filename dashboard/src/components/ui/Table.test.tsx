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
   * UPDATED BY D4 -- this test was inverted on purpose, and D4 is the
   * workstream it was waiting for.
   *
   * D1 wrote it as "does NOT reset to page 1 when the data shrinks (known
   * defect)", asserting the broken behaviour and explaining: "`page` is never
   * reset when `data` changes, so a caller who filters a list while the user
   * is on page 3 leaves the table showing an out-of-range slice: an empty body
   * under a 'Page 3 of 1' pager, with Previous enabled and Next disabled. ...
   * The fix ... is a change to Table.tsx, which this workstream does not own --
   * D4 owns the design system. So this test locks in the current behaviour and
   * the bug is reported instead. Whoever fixes it will see this test fail,
   * which is exactly the signal they want."
   *
   * That signal arrived. `Table` now clamps `page` into range whenever the
   * data identity, the sort or the page count changes, so the assertion is
   * flipped to describe the fixed behaviour. This is the one D1 test whose
   * expectations changed, and it changed in the direction D1 asked for -- not
   * weakened to make something pass.
   */
  it("clamps back into range when the data shrinks under the current page", async () => {
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

    // Both surviving rows are visible under an honest pager, instead of an
    // empty body under a "Page 3 of 1" that contradicts itself.
    expect(screen.queryByText("No data")).not.toBeInTheDocument();
    expect(bodyRows()).toHaveLength(2);
    expect(screen.queryByText("Page 3 of 1")).not.toBeInTheDocument();
  });

  it("keeps the operator on their page when it still exists", async () => {
    const { rerender } = render(
      <Table columns={COLUMNS} data={many} rowKey={(r) => r.id} pageSize={3} />,
    );
    await userEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(screen.getByText("Page 2 of 3")).toBeInTheDocument();

    // Six rows still means three pages, so page 2 is still valid. Clamping
    // rather than always resetting to 0 is what stops a background refetch
    // from yanking the operator back to the top of a list they were reading.
    rerender(
      <Table columns={COLUMNS} data={many.slice(0, 6)} rowKey={(r) => r.id} pageSize={3} />,
    );

    expect(screen.getByText("Page 2 of 2")).toBeInTheDocument();
  });
});

/**
 * ADDED BY D4. The dashboard audit (§6, Table.tsx:96,136) found the sort
 * headers and the row click were mouse-only: `onClick` on a bare `<th>` and a
 * bare `<tr>`, with no role, no tabIndex and no key handler. Column sort and
 * row activation were simply unavailable to a keyboard or screen-reader user.
 */
describe("Table keyboard and screen-reader access", () => {
  it("exposes each sortable header as a button", () => {
    render(<Table columns={COLUMNS} data={ROWS} rowKey={(r) => r.id} />);

    expect(screen.getByRole("button", { name: /Fare/ })).toBeInTheDocument();
    // A column that is not sortable stays plain text -- no fake affordance.
    expect(screen.queryByRole("button", { name: /^ID/ })).not.toBeInTheDocument();
  });

  it("sorts from the keyboard", async () => {
    render(<Table columns={COLUMNS} data={ROWS} rowKey={(r) => r.id} />);

    screen.getByRole("button", { name: /Fare/ }).focus();
    await userEvent.keyboard("{Enter}");
    expect(bodyRows().map((c) => c[1])).toEqual(["$7.25", "$19.00", "$42.50"]);

    await userEvent.keyboard(" ");
    expect(bodyRows().map((c) => c[1])).toEqual(["$42.50", "$19.00", "$7.25"]);
  });

  it("announces the current sort with aria-sort", async () => {
    render(<Table columns={COLUMNS} data={ROWS} rowKey={(r) => r.id} />);
    const header = screen.getByRole("columnheader", { name: /Fare/ });

    expect(header).toHaveAttribute("aria-sort", "none");

    await userEvent.click(screen.getByRole("button", { name: /Fare/ }));
    expect(header).toHaveAttribute("aria-sort", "ascending");

    await userEvent.click(screen.getByRole("button", { name: /Fare/ }));
    expect(header).toHaveAttribute("aria-sort", "descending");
  });

  it("gives no aria-sort to an unsortable column", () => {
    render(<Table columns={COLUMNS} data={ROWS} rowKey={(r) => r.id} />);

    expect(screen.getByRole("columnheader", { name: "ID" })).not.toHaveAttribute("aria-sort");
  });

  it("activates a row with Enter and Space when it is clickable", async () => {
    const onRowClick = vi.fn();
    render(<Table columns={COLUMNS} data={ROWS} rowKey={(r) => r.id} onRowClick={onRowClick} />);
    const firstRow = screen.getAllByRole("row")[1];

    expect(firstRow).toHaveAttribute("tabindex", "0");
    firstRow.focus();

    await userEvent.keyboard("{Enter}");
    expect(onRowClick).toHaveBeenCalledWith(ROWS[0]);

    await userEvent.keyboard(" ");
    expect(onRowClick).toHaveBeenCalledTimes(2);
  });

  it("leaves rows out of the tab order when they are not clickable", () => {
    render(<Table columns={COLUMNS} data={ROWS} rowKey={(r) => r.id} />);

    // An inert row that advertised itself as focusable would be a lie, and
    // would add three dead Tab stops per row to every table on the page.
    expect(screen.getAllByRole("row")[1]).not.toHaveAttribute("tabindex");
  });

  it("announces the async result: loading, then how many rows arrived", () => {
    const { container, rerender } = render(
      <Table columns={COLUMNS} data={[]} rowKey={(r) => r.id} isLoading />,
    );
    const region = () => container.querySelector("[aria-live]");

    expect(region()).toHaveTextContent("Loading table data");

    rerender(<Table columns={COLUMNS} data={ROWS} rowKey={(r) => r.id} />);

    // The swap from a loading row to real rows used to be a purely visual
    // change -- nothing told a screen-reader user the fetch had finished.
    expect(region()).toHaveTextContent("3 rows");
  });

  it("announces an empty result, so a filter that matched nothing is not silent", () => {
    const { container } = render(<Table columns={COLUMNS} data={[]} rowKey={(r) => r.id} />);

    expect(container.querySelector("[aria-live]")).toHaveTextContent("0 rows");
  });

  it("takes an accessible name for the table itself", () => {
    render(<Table columns={COLUMNS} data={ROWS} rowKey={(r) => r.id} label="Trips" />);

    expect(screen.getByRole("table", { name: "Trips" })).toBeInTheDocument();
  });
});
