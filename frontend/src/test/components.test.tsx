import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Button, ConfirmDialog, EmptyState, Input, Modal, Pagination, PriorityBadge, SlaIndicator, StatusBadge, Table, Tabs, Skeleton } from '../components/ui';
import { BarChart, DonutChart, LineChart } from '../components/charts/Charts';
import { formatCountdown, formatMinutes } from '../lib/format';

describe('form components', () => {
  it('Input links label, error and aria-invalid', () => {
    render(<Input label="Email" error="Required" />);
    const input = screen.getByLabelText('Email');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Required');
  });
  it('Button loading disables and marks busy', () => {
    render(<Button loading>Save</Button>);
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Save' })).toHaveAttribute('aria-busy', 'true');
  });
});

describe('Modal', () => {
  function Harness({ onClose = () => undefined }: { onClose?: () => void }) {
    const [open, setOpen] = useState(false);
    return (
      <>
        <button onClick={() => setOpen(true)}>open</button>
        <Modal open={open} title="Edit thing" onClose={() => { onClose(); setOpen(false); }}>
          <button>first</button><button>last</button>
        </Modal>
      </>
    );
  }
  it('is a labelled dialog, moves focus in, traps Tab, closes on Escape and restores focus', async () => {
    const onClose = vi.fn();
    render(<Harness onClose={onClose} />);
    const opener = screen.getByRole('button', { name: 'open' });
    await userEvent.click(opener);
    const dlg = screen.getByRole('dialog', { name: 'Edit thing' });
    expect(dlg).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByRole('button', { name: 'first' })).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByRole('button', { name: 'last' })).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByRole('button', { name: 'first' })).toHaveFocus(); // wrapped, not escaped
    await userEvent.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });
  it('ConfirmDialog calls handlers', async () => {
    const onConfirm = vi.fn(); const onCancel = vi.fn();
    render(<ConfirmDialog open title="Sure?" message="Really?" confirmLabel="Yes" onConfirm={onConfirm} onCancel={onCancel} />);
    await userEvent.click(screen.getByRole('button', { name: 'Yes' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});

describe('Tabs', () => {
  it('supports roving tabindex and arrow keys', async () => {
    function T() {
      const [a, setA] = useState('PUBLIC');
      return <Tabs idBase="x" label="vis" active={a} onChange={setA} tabs={[{ id: 'PUBLIC', label: 'Public' }, { id: 'INTERNAL', label: 'Internal' }]} />;
    }
    render(<T />);
    const pub = screen.getByRole('tab', { name: 'Public' });
    const internal = screen.getByRole('tab', { name: 'Internal' });
    expect(pub).toHaveAttribute('aria-selected', 'true');
    expect(internal).toHaveAttribute('tabindex', '-1');
    pub.focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(internal).toHaveAttribute('aria-selected', 'true');
    expect(internal).toHaveFocus();
  });
});

describe('display components', () => {
  it('badges render humanized labels', () => {
    render(<><PriorityBadge priority="URGENT" /><StatusBadge status="ON_HOLD" /></>);
    expect(screen.getByText('Urgent')).toBeInTheDocument();
    expect(screen.getByText('On hold')).toBeInTheDocument();
  });
  it('SlaIndicator shows overdue countdown', () => {
    render(<SlaIndicator status="BREACHED" due={new Date(Date.now() - 3 * 3600_000).toISOString()} />);
    expect(screen.getByText('Breached')).toBeInTheDocument();
    expect(screen.getByText(/overdue/)).toBeInTheDocument();
  });
  it('SlaIndicator hides countdown when MET', () => {
    render(<SlaIndicator status="MET" due={new Date(Date.now() + 3600_000).toISOString()} />);
    expect(screen.queryByText(/left/)).not.toBeInTheDocument();
  });
  it('Skeleton and EmptyState expose status roles', () => {
    render(<><Skeleton label="Loading x" /><EmptyState title="Nothing here" /></>);
    expect(screen.getByRole('status', { name: 'Loading x' })).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText('Nothing here')).toBeInTheDocument();
  });
  it('Pagination disables bounds and emits page', async () => {
    const onPage = vi.fn();
    render(<Pagination page={0} totalPages={3} onPage={onPage} />);
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(onPage).toHaveBeenCalledWith(1);
  });
  it('Table sets aria-sort on the active column', async () => {
    const onSort = vi.fn();
    render(<Table caption="c" rows={[{ id: '1', n: 'a' }]} rowKey={(r) => r.id} sort="n,desc" onSort={onSort}
      columns={[{ key: 'n', header: 'Name', sortField: 'n', render: (r) => r.n }]} />);
    expect(screen.getByRole('columnheader', { name: /Name/ })).toHaveAttribute('aria-sort', 'descending');
    await userEvent.click(screen.getByRole('button', { name: /Name/ }));
    expect(onSort).toHaveBeenCalledWith('n');
  });
});

describe('charts and formatting', () => {
  it('charts expose a text summary for screen readers', () => {
    render(<><BarChart title="Workload" data={[{ label: 'Ann', value: 3 }]} /><DonutChart title="Priority" data={[{ label: 'High', value: 2 }]} /><LineChart title="Trend" labels={['2026-01-01', '2026-01-02']} series={[{ name: 'Created', color: '#000', points: [1, 2] }]} /></>);
    expect(screen.getByRole('img', { name: 'Workload: Ann 3' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Priority: High 2' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /Trend: Created total 3/ })).toBeInTheDocument();
  });
  it('formatCountdown and formatMinutes', () => {
    const now = Date.parse('2026-01-01T12:00:00Z');
    expect(formatCountdown('2026-01-01T13:30:00Z', now)).toEqual({ text: '1h 30m left', overdue: false });
    expect(formatCountdown('2026-01-01T11:00:00Z', now)).toEqual({ text: '1h 0m overdue', overdue: true });
    expect(formatMinutes(45)).toBe('45m');
    expect(formatMinutes(90)).toBe('1.5h');
    expect(formatMinutes(undefined)).toBe('—');
  });
});
