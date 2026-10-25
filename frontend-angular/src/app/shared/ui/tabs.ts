import { ChangeDetectionStrategy, Component, ElementRef, input, output, viewChildren } from '@angular/core';

export interface TabDef { id: string; label: string; badge?: number | string; tone?: 'public' | 'internal' }

/** WAI-ARIA tabs with roving tabindex and arrow-key navigation. Panels are rendered by the caller (id `${idBase}-panel-${id}`). */
@Component({
  selector: 'app-tabs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tabs" role="tablist" [attr.aria-label]="label()" >
      @for (t of tabs(); track t.id) {
        <button #tabBtn type="button" role="tab" [id]="idBase() + '-tab-' + t.id"
                [attr.aria-selected]="active() === t.id" [attr.aria-controls]="idBase() + '-panel-' + t.id"
                [attr.tabindex]="active() === t.id ? 0 : -1"
                [class]="'tab' + (active() === t.id ? ' tab-active' : '') + (t.tone ? ' tab-' + t.tone : '')"
                (click)="activeChange.emit(t.id)" (keydown)="onKeyDown($event)">
          {{ t.label }}@if (t.badge !== undefined) { <span class="tab-badge">{{ t.badge }}</span> }
        </button>
      }
    </div>
  `,
})
export class TabsComponent {
  readonly tabs = input.required<TabDef[]>();
  readonly active = input.required<string>();
  readonly idBase = input.required<string>();
  readonly label = input.required<string>();
  readonly activeChange = output<string>();
  private readonly buttons = viewChildren<ElementRef<HTMLButtonElement>>('tabBtn');

  protected onKeyDown(e: KeyboardEvent): void {
    const tabs = this.tabs();
    const i = tabs.findIndex((t) => t.id === this.active());
    let n = i;
    if (e.key === 'ArrowRight') n = (i + 1) % tabs.length;
    else if (e.key === 'ArrowLeft') n = (i - 1 + tabs.length) % tabs.length;
    else if (e.key === 'Home') n = 0;
    else if (e.key === 'End') n = tabs.length - 1;
    else return;
    e.preventDefault();
    this.activeChange.emit(tabs[n].id);
    this.buttons()[n]?.nativeElement.focus();
  }
}
