import type { ReactNode } from 'react';

export interface Column<T> {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
  /** server-side sort field; when set the header becomes a sort button */
  sortField?: string;
  className?: string;
}

interface TableProps<T> {
  caption: string;
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  sort?: string; // "field,dir"
  onSort?: (field: string) => void;
}

export function Table<T>({ caption, columns, rows, rowKey, sort, onSort }: TableProps<T>) {
  const [sf, sd] = (sort ?? '').split(',');
  return (
    <div className="table-wrap">
      <table className="table">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            {columns.map((c) => {
              const active = c.sortField && c.sortField === sf;
              return (
                <th key={c.key} scope="col" aria-sort={active ? (sd === 'asc' ? 'ascending' : 'descending') : undefined} className={c.className}>
                  {c.sortField && onSort ? (
                    <button type="button" className="th-sort" onClick={() => onSort(c.sortField as string)}>
                      {c.header}<span aria-hidden="true">{active ? (sd === 'asc' ? ' ▲' : ' ▼') : ''}</span>
                    </button>
                  ) : c.header}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={rowKey(r)}>
              {columns.map((c) => <td key={c.key} className={c.className}>{c.render(r)}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
