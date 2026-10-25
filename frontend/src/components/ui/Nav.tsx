import { useRef, type KeyboardEvent, type ReactNode } from 'react';

export interface TabDef { id: string; label: string; badge?: ReactNode; tone?: 'public' | 'internal' }

/** WAI-ARIA tabs with roving tabindex and arrow-key navigation. Panels are rendered by the caller (id `${idBase}-panel-${id}`). */
export function Tabs({ tabs, active, onChange, idBase, label }: { tabs: TabDef[]; active: string; onChange: (id: string) => void; idBase: string; label: string }) {
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});
  function onKeyDown(e: KeyboardEvent) {
    const i = tabs.findIndex((t) => t.id === active);
    let n = i;
    if (e.key === 'ArrowRight') n = (i + 1) % tabs.length;
    else if (e.key === 'ArrowLeft') n = (i - 1 + tabs.length) % tabs.length;
    else if (e.key === 'Home') n = 0;
    else if (e.key === 'End') n = tabs.length - 1;
    else return;
    e.preventDefault();
    onChange(tabs[n].id);
    refs.current[tabs[n].id]?.focus();
  }
  return (
    <div className="tabs" role="tablist" aria-label={label} onKeyDown={onKeyDown}>
      {tabs.map((t) => (
        <button
          key={t.id}
          ref={(el) => { refs.current[t.id] = el; }}
          type="button"
          role="tab"
          id={`${idBase}-tab-${t.id}`}
          aria-selected={active === t.id}
          aria-controls={`${idBase}-panel-${t.id}`}
          tabIndex={active === t.id ? 0 : -1}
          className={`tab${active === t.id ? ' tab-active' : ''}${t.tone ? ` tab-${t.tone}` : ''}`}
          onClick={() => onChange(t.id)}
        >
          {t.label}{t.badge !== undefined && <span className="tab-badge">{t.badge}</span>}
        </button>
      ))}
    </div>
  );
}

interface PaginationProps { page: number; totalPages: number; totalElements?: number; onPage: (p: number) => void }
export function Pagination({ page, totalPages, totalElements, onPage }: PaginationProps) {
  if (totalPages <= 1 && !totalElements) return null;
  return (
    <nav className="pagination" aria-label="Pagination">
      <button type="button" className="btn btn-secondary btn-sm" disabled={page <= 0} onClick={() => onPage(page - 1)}>Previous</button>
      <span aria-live="polite">Page {page + 1} of {Math.max(totalPages, 1)}{totalElements !== undefined ? ` · ${totalElements} total` : ''}</span>
      <button type="button" className="btn btn-secondary btn-sm" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>Next</button>
    </nav>
  );
}
