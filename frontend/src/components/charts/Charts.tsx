export interface Datum { label: string; value: number; color?: string }

const PALETTE = ['#2563eb', '#16a34a', '#d97706', '#dc2626', '#7c3aed', '#0891b2'];

export function BarChart({ data, title, width = 420 }: { data: Datum[]; title: string; width?: number }) {
  const rowH = 26;
  const labelW = 110;
  const height = Math.max(data.length, 1) * rowH + 8;
  const max = Math.max(1, ...data.map((d) => d.value));
  const summary = `${title}: ${data.map((d) => `${d.label} ${d.value}`).join(', ')}`;
  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="chart" role="img" aria-label={summary}>
      {data.map((d, i) => {
        const w = ((width - labelW - 40) * d.value) / max;
        return (
          <g key={d.label} transform={`translate(0 ${i * rowH + 4})`}>
            <text x={labelW - 8} y={14} textAnchor="end" className="chart-label">{d.label}</text>
            <rect x={labelW} y={2} width={Math.max(w, 1)} height={16} rx={3} fill={d.color ?? PALETTE[0]} />
            <text x={labelW + Math.max(w, 1) + 6} y={14} className="chart-value">{d.value}</text>
          </g>
        );
      })}
    </svg>
  );
}

export function DonutChart({ data, title, size = 180 }: { data: Datum[]; title: string; size?: number }) {
  const total = data.reduce((s, d) => s + d.value, 0);
  const r = 60, c = 2 * Math.PI * r;
  let offset = 0;
  const summary = `${title}: ${data.map((d) => `${d.label} ${d.value}`).join(', ')}`;
  return (
    <div className="donut">
      <svg viewBox="0 0 160 160" width={size} height={size} role="img" aria-label={summary}>
        <circle cx={80} cy={80} r={r} fill="none" stroke="#e5e7eb" strokeWidth={24} />
        {total > 0 && data.map((d, i) => {
          const len = (d.value / total) * c;
          const el = (
            <circle key={d.label} cx={80} cy={80} r={r} fill="none" stroke={d.color ?? PALETTE[i % PALETTE.length]} strokeWidth={24}
              strokeDasharray={`${len} ${c - len}`} strokeDashoffset={-offset} transform="rotate(-90 80 80)" />
          );
          offset += len;
          return el;
        })}
        <text x={80} y={86} textAnchor="middle" className="chart-total">{total}</text>
      </svg>
      <ul className="legend">
        {data.map((d, i) => (
          <li key={d.label}><span className="swatch" style={{ background: d.color ?? PALETTE[i % PALETTE.length] }} aria-hidden="true" />{d.label}: {d.value}</li>
        ))}
      </ul>
    </div>
  );
}

export interface Series { name: string; color: string; points: number[] }

export function LineChart({ labels, series, title, width = 560, height = 200 }: { labels: string[]; series: Series[]; title: string; width?: number; height?: number }) {
  const padL = 32, padB = 22, padT = 8, padR = 8;
  const max = Math.max(1, ...series.flatMap((s) => s.points));
  const n = Math.max(labels.length, 2);
  const x = (i: number) => padL + ((width - padL - padR) * i) / (n - 1);
  const y = (v: number) => padT + (height - padT - padB) * (1 - v / max);
  const summary = `${title}: ${series.map((s) => `${s.name} total ${s.points.reduce((a, b) => a + b, 0)}`).join(', ')} over ${labels.length} days`;
  // at most 5 evenly spaced x labels; the first/last are anchored inward so they are never clipped at the SVG edges
  const tickCount = Math.min(labels.length, 5);
  const tickIdx = Array.from({ length: tickCount }, (_, k) => (tickCount <= 1 ? 0 : Math.round((k * (labels.length - 1)) / (tickCount - 1))));
  return (
    <div>
      <svg viewBox={`0 0 ${width} ${height}`} className="chart" role="img" aria-label={summary}>
        {[0, 0.5, 1].map((f) => (
          <g key={f}>
            <line x1={padL} x2={width - padR} y1={y(max * f)} y2={y(max * f)} stroke="#e5e7eb" />
            <text x={padL - 4} y={y(max * f) + 4} textAnchor="end" className="chart-label">{Math.round(max * f)}</text>
          </g>
        ))}
        {series.map((s) => (
          <polyline key={s.name} fill="none" stroke={s.color} strokeWidth={2}
            points={s.points.map((p, i) => `${x(i)},${y(p)}`).join(' ')} />
        ))}
        {tickIdx.map((i, k) => (
          <text key={i} x={x(i)} y={height - 6} textAnchor={k === 0 ? 'start' : k === tickIdx.length - 1 && k > 0 ? 'end' : 'middle'} className="chart-label">{labels[i]?.slice(5)}</text>
        ))}
      </svg>
      <ul className="legend legend-inline">
        {series.map((s) => <li key={s.name}><span className="swatch" style={{ background: s.color }} aria-hidden="true" />{s.name}</li>)}
      </ul>
    </div>
  );
}
