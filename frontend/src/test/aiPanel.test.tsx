import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AiPanel, AI_LABEL } from '../components/AiPanel';
import { ApiError } from '../api/client';
import type { AiSuggestion } from '../api/types';
import { makeUser, renderApp } from './utils';

vi.mock('../api/endpoints', () => ({
  aiApi: { analyze: vi.fn(), suggestions: vi.fn(), approve: vi.fn(), reject: vi.fn() },
}));
import { aiApi } from '../api/endpoints';
const m = aiApi as unknown as Record<'analyze' | 'suggestions' | 'approve' | 'reject', ReturnType<typeof vi.fn>>;

const suggestion = (over: Partial<AiSuggestion> = {}): AiSuggestion => ({
  suggestionId: 's1', provider: 'MOCK_RULE_BASED', generated: true, summary: 'User cannot connect to VPN', suggestedCategory: { id: 'c', name: 'Network' },
  suggestedPriority: 'HIGH', confidence: 0.82, explanation: 'Matched keyword vpn', recommendedArticles: [{ id: 'a1', title: 'VPN troubleshooting', score: 0.9 }],
  suggestedReply: 'Please restart your VPN client.', status: 'PENDING_REVIEW', degraded: false, fallbackMessage: null, ...over,
});

const setup = () => renderApp(<AiPanel ticketId="t1" />, { user: makeUser(['AGENT']) });
beforeEach(() => { Object.values(m).forEach((f) => f.mockReset()); m.suggestions.mockResolvedValue([]); });

describe('AiPanel', () => {
  it('idle -> pending (loading) -> result with label, confidence, explanation, articles', async () => {
    let resolve!: (s: AiSuggestion) => void;
    m.analyze.mockReturnValue(new Promise<AiSuggestion>((r) => { resolve = r; }));
    setup();
    await userEvent.click(await screen.findByRole('button', { name: 'Analyze ticket' }));
    expect(await screen.findByRole('status', { name: 'AI analysis in progress' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Analyze ticket' })).not.toBeInTheDocument();
    resolve(suggestion());
    expect(await screen.findByText('User cannot connect to VPN')).toBeInTheDocument();
    expect(screen.getByRole('meter', { name: 'AI confidence' })).toHaveAttribute('aria-valuenow', '82');
    expect(screen.getByText('Matched keyword vpn')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'VPN troubleshooting' })).toHaveAttribute('href', '/kb/a1');
    expect(screen.getAllByText(new RegExp(AI_LABEL.replace(/[()]/g, '\\$&'))).length).toBeGreaterThan(0);
    expect(m.approve).not.toHaveBeenCalled(); // nothing is sent without approval
  });

  it('degraded:true shows fallback message and no reply text/approve button', async () => {
    m.analyze.mockResolvedValue(suggestion({ degraded: true, fallbackMessage: 'AI is degraded, work manually', suggestedReply: null, summary: '' }));
    setup();
    await userEvent.click(await screen.findByRole('button', { name: 'Analyze ticket' }));
    expect(await screen.findByTestId('ai-degraded')).toHaveTextContent('AI is degraded, work manually');
    expect(screen.queryByRole('button', { name: /Approve/ })).not.toBeInTheDocument();
    expect(screen.queryByTestId('ai-result')).not.toBeInTheDocument();
  });

  it('503 AI_UNAVAILABLE also shows the degraded message', async () => {
    m.analyze.mockRejectedValue(new ApiError(503, 'AI_UNAVAILABLE', 'down'));
    setup();
    await userEvent.click(await screen.findByRole('button', { name: 'Analyze ticket' }));
    expect(await screen.findByTestId('ai-degraded')).toHaveTextContent(/temporarily unavailable/i);
  });

  it('approve requires confirmation; unchanged reply sends no editedReply', async () => {
    m.analyze.mockResolvedValue(suggestion());
    m.approve.mockResolvedValue(suggestion({ status: 'APPROVED' }));
    setup();
    await userEvent.click(await screen.findByRole('button', { name: 'Analyze ticket' }));
    await userEvent.click(await screen.findByRole('button', { name: /Approve & post reply/ }));
    expect(m.approve).not.toHaveBeenCalled();
    const dlg = await screen.findByRole('dialog');
    await userEvent.click(within(dlg).getByRole('button', { name: 'Approve & post' }));
    await waitFor(() => expect(m.approve).toHaveBeenCalledWith('s1', undefined));
    expect(await screen.findByText('Approved; reply posted.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Approve & post reply/ })).not.toBeInTheDocument();
  });

  it('edited reply is sent as editedReply', async () => {
    m.analyze.mockResolvedValue(suggestion());
    m.approve.mockResolvedValue(suggestion({ status: 'EDITED_APPROVED' }));
    setup();
    await userEvent.click(await screen.findByRole('button', { name: 'Analyze ticket' }));
    const box = await screen.findByLabelText('Edit before approving');
    await userEvent.clear(box);
    await userEvent.type(box, 'Custom reply');
    await userEvent.click(screen.getByRole('button', { name: /Approve & post reply/ }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Approve & post' }));
    await waitFor(() => expect(m.approve).toHaveBeenCalledWith('s1', 'Custom reply'));
    expect(await screen.findByText('Approved after editing; reply posted.')).toBeInTheDocument();
  });

  it('reject needs a reason and never calls approve', async () => {
    m.analyze.mockResolvedValue(suggestion());
    m.reject.mockResolvedValue(suggestion({ status: 'REJECTED' }));
    setup();
    await userEvent.click(await screen.findByRole('button', { name: 'Analyze ticket' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Reject' }));
    const confirm = screen.getByRole('button', { name: 'Confirm reject' });
    expect(confirm).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Reason for rejecting'), 'Not accurate');
    await userEvent.click(confirm);
    await waitFor(() => expect(m.reject).toHaveBeenCalledWith('s1', 'Not accurate'));
    expect(await screen.findByText('This suggestion was rejected.')).toBeInTheDocument();
    expect(m.approve).not.toHaveBeenCalled();
  });

  it('restores a pending suggestion from history on mount', async () => {
    m.suggestions.mockResolvedValue([suggestion()]);
    setup();
    expect(await screen.findByText('User cannot connect to VPN')).toBeInTheDocument();
    expect(m.analyze).not.toHaveBeenCalled();
  });
});
