import { AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, HostListener, inject, input, OnDestroy, output, viewChild } from '@angular/core';

let seq = 0;

/**
 * Accessible modal dialog: role=dialog + aria-modal, labelled by its title, traps Tab focus, closes on Esc /
 * backdrop click, focuses `[data-autofocus]` (or the first focusable element) and restores focus to the trigger.
 * Render it conditionally (`@if (open) { <app-modal> }`); it is "open" for as long as it exists.
 */
@Component({
  selector: 'app-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="modal-backdrop" (mousedown)="onBackdrop($event)">
      <div #dialog class="modal" role="dialog" aria-modal="true" [attr.aria-labelledby]="titleId">
        <h2 class="modal-title" [id]="titleId">{{ title() }}</h2>
        <ng-content />
      </div>
    </div>
  `,
})
export class ModalComponent implements AfterViewInit, OnDestroy {
  readonly title = input.required<string>();
  readonly closed = output<void>();
  protected readonly titleId = `modal-title-${++seq}`;
  private readonly dialog = viewChild.required<ElementRef<HTMLElement>>('dialog');
  private opener: HTMLElement | null = typeof document !== 'undefined' ? (document.activeElement as HTMLElement | null) : null;
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  ngAfterViewInit(): void {
    queueMicrotask(() => {
      const el = this.dialog().nativeElement;
      const target = el.querySelector<HTMLElement>('[data-autofocus]') ?? this.focusables()[0] ?? el;
      target.focus();
    });
  }
  ngOnDestroy(): void {
    this.opener?.focus?.();
  }

  protected onBackdrop(e: MouseEvent): void {
    if (e.target === e.currentTarget) this.closed.emit();
  }

  private focusables(): HTMLElement[] {
    const sel = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
    return Array.from(this.dialog().nativeElement.querySelectorAll<HTMLElement>(sel));
  }

  @HostListener('document:keydown', ['$event'])
  onKey(e: KeyboardEvent): void {
    if (!this.host.nativeElement.isConnected) return;
    if (e.key === 'Escape') { e.stopPropagation(); this.closed.emit(); return; }
    if (e.key !== 'Tab') return;
    const f = this.focusables();
    if (f.length === 0) { e.preventDefault(); return; }
    const first = f[0], last = f[f.length - 1];
    const active = document.activeElement;
    if (e.shiftKey && active === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && active === last) { e.preventDefault(); first.focus(); }
  }
}
