import { Outlet } from "react-router-dom";
import { Sidebar } from "./Sidebar";

/** Authenticated app frame: brand sidebar + scrollable content area for the active route. */
export function AppShell() {
  return (
    <div className="flex h-screen bg-brand-lavender">
      <Sidebar />
      <main className="flex-1 overflow-y-auto p-6">
        <Outlet />
      </main>
    </div>
  );
}
