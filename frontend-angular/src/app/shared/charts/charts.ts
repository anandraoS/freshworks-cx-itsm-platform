import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export interface Datum { label: string; value: number; color?: string }
export interface Series { name: string; color: string; points: number[] }

const PALETTE = ['#2563eb', '#16a34a', '#d97706', '#dc2626', '#7c3aed', '#0891b2'];

/** Horizontal bar chart in inline SVG with a text alternative (`role="img"` + summary label). */
@Component({
  selector: 'app-bar-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg [attr.viewBox]="'0 0 ' + width + ' ' + height()" class="chart" role="img" [attr.aria-label]="summary()">
      @for (b of bars(); track b.label) {
        <g [attr.transform]="'translate(0 ' + b.y + ')'">
          <text [attr.x]="labelW - 8" y="14" text-anchor="end" class="chart-label">{{ b.label }}</text>
          <rect [attr.x]="labelW" y="2" [attr.width]="b.w" height="16" rx="3" [attr.fill]="b.color" />
          <text [attr.x]="labelW + b.w + 6" y="14" class="chart-value">{{ b.value }}</text>
        </g>
      }
    </svg>
  `,
})
export class BarChartComponent {
  readonly data = input.required<Datum[]>();
  readonly title = input.required<string>();
  protected readonly width = 420;
  protected readonly labelW = 110;
  private readonly rowH = 26;
  protected readonly height = computed(() => Math.max(this.data().length, 1) * this.rowH + 8);
  protected readonly bars = computed(() => {
    const d = this.data();
    const max = Math.max(1, ...d.map((x) => x.value));
    return d.map((x, i) => ({
      label: x.label, value: x.value, color: x.color ?? PALETTE[0], y: i * this.rowH + 4,
      w: Math.max(((this.width - this.labelW - 40) * x.value) / max, 1),
    }));
  });
  protected readonly summary = computed(() => `${this.title()}: ${this.data().map((d) => `${d.label} ${d.value}`).join(', ')}`);
}

@Component({
  selector: 'app-donut-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="donut">
      <svg viewBox="0 0 160 160" width="180" height="180" role="img" [attr.aria-label]="summary()">
        <circle cx="80" cy="80" [attr.r]="r" fill="none" stroke="#e5e7eb" stroke-width="24" />
        @if (total() > 0) {
          @for (a of arcs(); track a.label) {
            <circle cx="80" cy="80" [attr.r]="r" fill="none" [attr.stroke]="a.color" stroke-width="24"
                    [attr.stroke-dasharray]="a.len + ' ' + (c - a.len)" [attr.stroke-dashoffset]="-a.offset" transform="rotate(-90 80 80)" />
          }
        }
        <text x="80" y="86" text-anchor="middle" class="chart-total">{{ total() }}</text>
      </svg>
      <ul class="legend">
        @for (d of data(); track d.label; let i = $index) {
          <li><span class="swatch" [style.background]="d.color ?? palette[i % palette.length]" aria-hidden="true"></span>{{ d.label }}: {{ d.value }}</li>
        }
      </ul>
    </div>
  `,
})
export class DonutChartComponent {
  readonly data = input.required<Datum[]>();
  readonly title = input.required<string>();
  protected readonly palette = PALETTE;
  protected readonly r = 60;
  protected readonly c = 2 * Math.PI * 60;
  protected readonly total = computed(() => this.data().reduce((s, d) => s + d.value, 0));
  protected readonly arcs = computed(() => {
    const total = this.total();
    let offset = 0;
    return this.data().map((d, i) => {
      const len = total > 0 ? (d.value / total) * this.c : 0;
      const a = { label: d.label, color: d.color ?? PALETTE[i % PALETTE.length], len, offset };
      offset += len;
      return a;
    });
  });
  protected readonly summary = computed(() => `${this.title()}: ${this.data().map((d) => `${d.label} ${d.value}`).join(', ')}`);
}

@Component({
  selector: 'app-line-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div>
      <svg [attr.viewBox]="'0 0 ' + width + ' ' + height" class="chart" role="img" [attr.aria-label]="summary()">
        @for (g of grid(); track g.f) {
          <g>
            <line [attr.x1]="padL" [attr.x2]="width - padR" [attr.y1]="g.y" [attr.y2]="g.y" stroke="#e5e7eb" />
            <text [attr.x]="padL - 4" [attr.y]="g.y + 4" text-anchor="end" class="chart-label">{{ g.text }}</text>
          </g>
        }
        @for (l of lines(); track l.name) {
          <polyline fill="none" [attr.stroke]="l.color" stroke-width="2" [attr.points]="l.points" />
        }
        @for (t of ticks(); track t.x) {
          <text [attr.x]="t.x" [attr.y]="height - 6" [attr.text-anchor]="t.anchor" class="chart-label">{{ t.text }}</text>
        }
      </svg>
      <ul class="legend legend-inline">
        @for (s of series(); track s.name) { <li><span class="swatch" [style.background]="s.color" aria-hidden="true"></span>{{ s.name }}</li> }
      </ul>
    </div>
  `,
})
export class LineChartComponent {
  readonly labels = input.required<string[]>();
  readonly series = input.required<Series[]>();
  readonly title = input.required<string>();
  protected readonly width = 560;
  protected readonly height = 200;
  protected readonly padL = 32;
  protected readonly padR = 8;
  private readonly padB = 22;
  private readonly padT = 8;

  private readonly max = computed(() => Math.max(1, ...this.series().flatMap((s) => s.points)));
  private x(i: number): number { return this.padL + ((this.width - this.padL - this.padR) * i) / (Math.max(this.labels().length, 2) - 1); }
  private y(v: number): number { return this.padT + (this.height - this.padT - this.padB) * (1 - v / this.max()); }

  protected readonly grid = computed(() => [0, 0.5, 1].map((f) => ({ f, y: this.y(this.max() * f), text: Math.round(this.max() * f) })));
  protected readonly lines = computed(() => this.series().map((s) => ({ name: s.name, color: s.color, points: s.points.map((p, i) => `${this.x(i)},${this.y(p)}`).join(' ') })));
  protected readonly ticks = computed(() => {
    const l = this.labels();
    // at most 5 evenly spaced x labels; first/last are anchored inward so they are never clipped at the SVG edges
    const count = Math.min(l.length, 5);
    const idx = Array.from({ length: count }, (_, k) => (count <= 1 ? 0 : Math.round((k * (l.length - 1)) / (count - 1))));
    return idx.map((i, k) => ({ x: this.x(i), text: (l[i] ?? '').slice(5), anchor: k === 0 ? 'start' : k === count - 1 && k > 0 ? 'end' : 'middle' }));
  });
  protected readonly summary = computed(
    () => `${this.title()}: ${this.series().map((s) => `${s.name} total ${s.points.reduce((a, b) => a + b, 0)}`).join(', ')} over ${this.labels().length} days`,
  );
}
