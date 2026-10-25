import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ErrorBoundary } from '../components/ErrorBoundary';

let shouldThrow = true;
function Bomb() {
  if (shouldThrow) throw new Error('kaboom');
  return <p>recovered</p>;
}

afterEach(() => { shouldThrow = true; vi.restoreAllMocks(); });

describe('ErrorBoundary', () => {
  it('renders fallback instead of crashing and can recover', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    render(<ErrorBoundary><Bomb /></ErrorBoundary>);
    expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong');
    shouldThrow = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(screen.getByText('recovered')).toBeInTheDocument();
  });

  it('resets when resetKey changes (route change)', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const { rerender } = render(<ErrorBoundary resetKey="/a"><Bomb /></ErrorBoundary>);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    shouldThrow = false;
    rerender(<ErrorBoundary resetKey="/b"><Bomb /></ErrorBoundary>);
    expect(screen.getByText('recovered')).toBeInTheDocument();
  });

  it('logs only the message, not the error object', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    render(<ErrorBoundary><Bomb /></ErrorBoundary>);
    expect(spy.mock.calls.some((c) => c[0] === 'UI error boundary caught:' && c[1] === 'kaboom')).toBe(true);
  });
});
