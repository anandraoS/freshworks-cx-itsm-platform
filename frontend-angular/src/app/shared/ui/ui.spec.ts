import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { minLen } from '../../core/validators';
import { AiBadgeComponent, PriorityBadgeComponent, StatusBadgeComponent } from './badges';
import { ButtonComponent } from './button';
import { ConfidenceMeterComponent, EmptyStateComponent, ErrorNoticeComponent } from './feedback';
import { FieldComponent, FieldControlDirective } from './field';
import { ModalComponent } from './modal';
import { PaginationComponent } from './pagination';
import { SlaIndicatorComponent } from './sla-indicator';
import { TableComponent } from './table';
import { TabsComponent } from './tabs';

@Component({
  imports: [ButtonComponent, TableComponent, PaginationComponent, ModalComponent, TabsComponent, ErrorNoticeComponent],
  template: `
    <button appButton id="b" [loading]="loading()" (click)="clicks = clicks + 1">Save</button>
    <app-table caption="Things" [columns]="cols" [rows]="rows" [sort]="sort()" sortable (sortChange)="sorted = $event">
      <ng-template #cell let-row let-col="col">{{ col === 'name' ? row.name : row.n }}</ng-template>
    </app-table>
    <app-pagination [page]="1" [totalPages]="3" [totalElements]="57" (pageChange)="page = $event" />
    @if (modalOpen()) { <app-modal title="Edit thing" (closed)="modalOpen.set(false)"><input id="first" data-autofocus /><button id="second">x</button></app-modal> }
    <app-tabs [tabs]="tabs" [active]="tab()" idBase="t" label="Kinds" (activeChange)="tab.set($event)" />
    <app-error-notice message="Boom" retryable (retry)="retried = true" />
  `,
})
class HostComponent {
  clicks = 0;
  sorted = '';
  page = -1;
  retried = false;
  loading = signal(false);
  sort = signal('name,asc');
  modalOpen = signal(true);
  tab = signal('a');
  cols = [{ key: 'name', header: 'Name', sortField: 'name' }, { key: 'n', header: 'Count' }];
  rows = [{ id: '1', name: 'alpha', n: 1 }, { id: '2', name: 'beta', n: 2 }];
  tabs = [{ id: 'a', label: 'Public', badge: 2 }, { id: 'b', label: 'Internal', badge: 0 }, { id: 'c', label: 'Third' }];
}

describe('shared UI components', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;
  let el: HTMLElement;
  const q = <T extends Element>(s: string) => el.querySelector<T>(s) as T;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HostComponent] }).compileComponents();
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await fixture.whenStable();
  });

  it('button: disabled + spinner + aria-busy while loading, still a native button', () => {
    const b = q<HTMLButtonElement>('#b');
    expect(b.disabled).toBeFalse();
    expect(b.getAttribute('type')).toBe('button');
    host.loading.set(true);
    fixture.detectChanges();
    expect(b.disabled).toBeTrue();
    expect(b.getAttribute('aria-busy')).toBe('true');
    expect(b.querySelector('.spinner')).not.toBeNull();
  });

  it('table: caption, aria-sort on the sorted column, sort button emits the field', () => {
    expect(q('caption').textContent).toBe('Things');
    const th = el.querySelectorAll('th');
    expect(th[0].getAttribute('aria-sort')).toBe('ascending');
    expect(th[1].getAttribute('aria-sort')).toBeNull();
    expect(th[1].querySelector('button')).toBeNull();
    th[0].querySelector('button')?.click();
    expect(host.sorted).toBe('name');
    host.sort.set('name,desc');
    fixture.detectChanges();
    expect(th[0].getAttribute('aria-sort')).toBe('descending');
    expect(el.querySelectorAll('tbody tr').length).toBe(2);
  });

  it('pagination: page numbers, total and prev/next', () => {
    const nav = q<HTMLElement>('nav.pagination');
    expect(nav.textContent).toContain('Page 2 of 3');
    expect(nav.textContent).toContain('57 total');
    const [prev, next] = Array.from(nav.querySelectorAll('button'));
    next.click();
    expect(host.page).toBe(2);
    prev.click();
    expect(host.page).toBe(0);
  });

  it('modal: role=dialog, labelled, focuses [data-autofocus], closes on Escape', async () => {
    const dlg = q<HTMLElement>('[role=dialog]');
    expect(dlg.getAttribute('aria-modal')).toBe('true');
    expect(el.querySelector(`#${dlg.getAttribute('aria-labelledby')}`)?.textContent).toBe('Edit thing');
    await Promise.resolve();
    expect(document.activeElement?.id).toBe('first');
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(el.querySelector('[role=dialog]')).toBeNull();
  });

  it('modal: Tab wraps focus inside the dialog', async () => {
    await Promise.resolve();
    q<HTMLElement>('#second').focus();
    const ev = new KeyboardEvent('keydown', { key: 'Tab', cancelable: true });
    document.dispatchEvent(ev);
    expect(ev.defaultPrevented).toBeTrue();
    expect(document.activeElement?.id).toBe('first');
  });

  it('tabs: ARIA roles, roving tabindex and arrow-key navigation', () => {
    const tabs = Array.from(el.querySelectorAll<HTMLButtonElement>('[role=tab]'));
    expect(el.querySelector('[role=tablist]')?.getAttribute('aria-label')).toBe('Kinds');
    expect(tabs.map((t) => t.getAttribute('aria-selected'))).toEqual(['true', 'false', 'false']);
    expect(tabs.map((t) => t.getAttribute('tabindex'))).toEqual(['0', '-1', '-1']);
    tabs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
    fixture.detectChanges();
    expect(host.tab()).toBe('b');
    tabs[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true, cancelable: true }));
    expect(host.tab()).toBe('c');
  });

  it('error notice: role=alert and retry button', () => {
    const n = q<HTMLElement>('.notice-error');
    expect(n.getAttribute('role')).toBe('alert');
    n.querySelector('button')?.click();
    expect(host.retried).toBeTrue();
  });
});

@Component({
  imports: [ReactiveFormsModule, FieldComponent, FieldControlDirective, PriorityBadgeComponent, StatusBadgeComponent, AiBadgeComponent, ConfidenceMeterComponent, EmptyStateComponent, SlaIndicatorComponent],
  template: `
    <app-field label="Title" hint="Short and clear" [control]="ctrl" required><input appFieldControl [formControl]="ctrl" /></app-field>
    <app-priority-badge priority="URGENT" /><app-status-badge status="ON_HOLD" /><app-ai-badge />
    <app-confidence-meter [value]="0.876" />
    <app-empty-state title="Nothing here" description="Try later" />
    <div id="sla1"><app-sla-indicator status="BREACHED" [due]="past" /></div>
    <div id="sla2"><app-sla-indicator status="MET" [due]="past" /></div>
    <div id="sla3"><app-sla-indicator status="ON_TRACK" [due]="future" /></div>
  `,
})
class DisplayHost {
  ctrl = new FormControl('', { nonNullable: true, validators: [minLen(5, 'Title must be at least 5 characters')] });
  past = new Date(Date.now() - 3 * 3600_000).toISOString();
  future = new Date(Date.now() + 2 * 3600_000 + 60_000).toISOString();
}

describe('display components', () => {
  let fixture: ComponentFixture<DisplayHost>;
  let el: HTMLElement;
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [DisplayHost] }).compileComponents();
    fixture = TestBed.createComponent(DisplayHost);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  it('field: label bound to the control, hint first, error only after touched, aria wiring', () => {
    const input = el.querySelector('input') as HTMLInputElement;
    const label = el.querySelector('label') as HTMLLabelElement;
    expect(label.getAttribute('for')).toBe(input.id);
    expect(label.textContent).toContain('Title');
    expect(el.querySelector('.field-hint')?.textContent).toBe('Short and clear');
    expect(el.querySelector('.field-msg')).toBeNull();
    expect(input.getAttribute('aria-invalid')).toBeNull();
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
    const msg = el.querySelector('.field-msg') as HTMLElement;
    expect(msg.textContent).toBe('Title must be at least 5 characters');
    expect(msg.getAttribute('role')).toBe('alert');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(input.getAttribute('aria-describedby')).toBe(msg.id);
    expect(el.querySelector('.field-hint')).toBeNull();
  });

  it('badges convey meaning with text, not only colour', () => {
    expect(el.querySelector('.badge-danger')?.textContent).toBe('Urgent');
    expect(el.querySelector('.badge-neutral')?.textContent).toBe('On hold');
    expect(el.querySelector('.badge-ai')?.textContent).toContain('AI-generated');
  });

  it('confidence meter exposes role=meter with a text value', () => {
    const m = el.querySelector('[role=meter]') as HTMLElement;
    expect(m.getAttribute('aria-valuenow')).toBe('88');
    expect(m.getAttribute('aria-valuetext')).toBe('88 percent');
  });

  it('SLA indicator: overdue countdown for BREACHED, none for MET, "left" when on track', () => {
    expect(el.querySelector('#sla1 .sla-count')?.textContent).toContain('overdue');
    expect(el.querySelector('#sla1 .sla-count')?.classList).toContain('overdue');
    expect(el.querySelector('#sla2 .sla-count')).toBeNull();
    expect(el.querySelector('#sla3 .sla-count')?.textContent).toContain('left');
  });

  it('empty state is announced as status', () => {
    const e = el.querySelector('.empty') as HTMLElement;
    expect(e.getAttribute('role')).toBe('status');
    expect(e.textContent).toContain('Nothing here');
  });
});
