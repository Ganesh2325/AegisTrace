import type { ReactNode } from "react";
import { cn } from "./cn";

export type Column<T> = {
  key: string;
  header: string;
  cell: (row: T) => ReactNode;
  className?: string;
  sortable?: boolean;
};

export function DataTable<T>({
  columns,
  rows,
  getKey,
  density = "compact",
  empty,
  loading,
  error,
  selectedKey,
  actions,
  pagination,
}: {
  columns: Column<T>[];
  rows: T[];
  getKey: (row: T) => string;
  density?: "compact" | "default";
  empty?: ReactNode;
  loading?: boolean;
  error?: ReactNode;
  selectedKey?: string;
  actions?: (row: T) => ReactNode;
  pagination?: ReactNode;
}) {
  if (loading) {
    return <div className="h-24 animate-pulse rounded-md border border-line bg-elevated" aria-busy="true" aria-label="Loading table" />;
  }
  if (error) return <>{error}</>;
  if (rows.length === 0) return <>{empty}</>;
  const pad = density === "compact" ? "py-2" : "py-3";
  return (
    <div className="overflow-x-auto rounded-md border border-line">
      <table className="w-full border-collapse text-left text-sm">
        <thead className="bg-surface text-[11px] uppercase tracking-[0.12em] text-muted">
          <tr>
            {columns.map((column) => (
              <th key={column.key} className={cn("px-3 font-medium", pad, column.className)} aria-sort={column.sortable ? "none" : undefined}>
                {column.sortable ? (
                  <button type="button" className="uppercase tracking-[0.12em]" disabled aria-disabled="true" title="Sorting is not connected">
                    {column.header}
                  </button>
                ) : column.header}
              </th>
            ))}
            {actions && <th className={cn("px-3 font-medium", pad)}>Actions</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const key = getKey(row);
            return (
              <tr key={key} className={cn("border-t border-line hover:bg-white/[0.03]", selectedKey === key && "bg-white/[0.06]")} aria-selected={selectedKey === key}>
                {columns.map((column) => (
                  <td key={column.key} className={cn("px-3 align-middle", pad, column.className)}>{column.cell(row)}</td>
                ))}
                {actions && <td className={cn("px-3 align-middle", pad)}>{actions(row)}</td>}
              </tr>
            );
          })}
        </tbody>
      </table>
      {pagination && <div className="border-t border-line px-3 py-2 text-xs text-muted">{pagination}</div>}
    </div>
  );
}
