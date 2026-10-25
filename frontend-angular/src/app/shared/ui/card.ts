import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Card with optional heading; slots: `[cardTitleExtra]` (next to the title) and `[cardActions]` (right side). */
@Component({
  selector: 'app-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class]': '"card " + cardClass()' },
  template: `
    @if (title()) {
      <header class="card-head">
        <h2 class="card-title">{{ title() }} <ng-content select="[cardTitleExtra]" /></h2>
        <div class="card-actions"><ng-content select="[cardActions]" /></div>
      </header>
    }
    <ng-content />
  `,
})
export class CardComponent {
  readonly title = input('');
  readonly cardClass = input('');
}
