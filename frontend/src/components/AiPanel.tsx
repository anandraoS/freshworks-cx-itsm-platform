import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { aiApi } from '../api/endpoints';
import { ApiError, describeError } from '../api/client';
import type { AiSuggestion } from '../api/types';
import { useTenantId } from '../auth/AuthContext';
import { qk } from '../lib/queryKeys';
import { AiBadge, Button, Card, ConfidenceMeter, ConfirmDialog, ErrorNotice, Skeleton, Textarea, useToast } from './ui';

export const AI_LABEL = 'AI-generated (mock rule-based provider)';
const DEFAULT_DEGRADED = 'AI assistance is temporarily unavailable. You can continue working on the ticket manually.';

/**
 * AI assist panel. Nothing is ever sent to the customer by the AI: the reply is only a draft that an
 * agent must edit/approve (POST approve posts it as a public comment) or reject.
 */
export function AiPanel({ ticketId }: { ticketId: string }) {
  const t = useTenantId();
  const qc = useQueryClient();
  const toast = useToast();
  const [result, setResult] = useState<AiSuggestion | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [reply, setReply] = useState('');
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);

  const history = useQuery({ queryKey: qk.aiSuggestions(t, ticketId), queryFn: () => aiApi.suggestions(ticketId) });

  // Show the latest pending suggestion from history when the user has not just analyzed.
  useEffect(() => {
    if (result || !history.data) return;
    const pending = history.data.find((s) => s.status === 'PENDING_REVIEW' && !s.degraded);
    if (pending) { setResult(pending); setReply(pending.suggestedReply ?? ''); }
  }, [history.data, result]);

  const analyze = useMutation({
    mutationFn: () => aiApi.analyze(ticketId),
    onMutate: () => { setError(null); setUnavailable(false); },
    onSuccess: (s) => { setResult(s); setReply(s.suggestedReply ?? ''); void qc.invalidateQueries({ queryKey: qk.aiSuggestions(t, ticketId) }); },
    onError: (e) => {
      if (e instanceof ApiError && e.kind === 'ai_unavailable') { setUnavailable(true); setResult(null); }
      else setError(describeError(e));
    },
  });

  const afterDecision = (s: AiSuggestion, msg: string) => {
    setResult(s);
    toast.push('success', msg);
    void qc.invalidateQueries({ queryKey: qk.comments(t, ticketId) });
    void qc.invalidateQueries({ queryKey: qk.activity(t, ticketId) });
    void qc.invalidateQueries({ queryKey: qk.aiSuggestions(t, ticketId) });
  };

  const approve = useMutation({
    mutationFn: () => {
      const edited = reply.trim() !== (result?.suggestedReply ?? '').trim();
      return aiApi.approve((result as AiSuggestion).suggestionId, edited ? reply.trim() : undefined);
    },
    onSuccess: (s) => { setConfirmOpen(false); afterDecision({ ...(result as AiSuggestion), ...s }, 'Reply approved and posted'); },
    onError: (e) => { setConfirmOpen(false); setError(describeError(e)); },
  });

  const reject = useMutation({
    mutationFn: () => aiApi.reject((result as AiSuggestion).suggestionId, reason.trim()),
    onSuccess: (s) => { setRejecting(false); setReason(''); afterDecision({ ...(result as AiSuggestion), ...s, status: 'REJECTED' }, 'Suggestion rejected'); },
    onError: (e) => setError(describeError(e)),
  });

  const degraded = unavailable || (result?.degraded ?? false);
  const decided = result && result.status !== 'PENDING_REVIEW';
  const busy = approve.isPending || reject.isPending;

  return (
    <Card title={<>AI assist <AiBadge /></>} className="ai-panel">
      <p className="muted small">{AI_LABEL}. Suggestions are drafts only: nothing is sent to the customer unless you approve it.</p>

      {error && <ErrorNotice message={error} />}

      {!result && !unavailable && !analyze.isPending && (
        <Button onClick={() => analyze.mutate()}>Analyze ticket</Button>
      )}

      {analyze.isPending && (
        <div aria-live="polite"><p>Analyzing…</p><Skeleton lines={4} label="AI analysis in progress" /></div>
      )}

      {degraded && (
        <div className="notice notice-warn" role="status" data-testid="ai-degraded">
          <span>{result?.fallbackMessage || DEFAULT_DEGRADED}</span>
          <Button size="sm" variant="secondary" onClick={() => { setResult(null); setUnavailable(false); analyze.mutate(); }} loading={analyze.isPending}>Try again</Button>
        </div>
      )}

      {result && !result.degraded && (
        <div className="ai-body" data-testid="ai-result">
          <section aria-labelledby="ai-summary-h">
            <h3 id="ai-summary-h">Summary</h3>
            <p>{result.summary}</p>
          </section>
          <section aria-label="Confidence">
            <h3>Confidence</h3>
            <ConfidenceMeter value={result.confidence} />
          </section>
          <section aria-labelledby="ai-expl-h">
            <h3 id="ai-expl-h">Why this suggestion</h3>
            <p>{result.explanation}</p>
          </section>
          {(result.suggestedCategory || result.suggestedPriority) && (
            <p className="small">Suggested category: <strong>{result.suggestedCategory?.name ?? '—'}</strong> · Suggested priority: <strong>{result.suggestedPriority ?? '—'}</strong> (informational; not applied automatically)</p>
          )}
          {result.recommendedArticles.length > 0 && (
            <section aria-labelledby="ai-art-h">
              <h3 id="ai-art-h">Recommended articles</h3>
              <ul>
                {result.recommendedArticles.map((a) => (
                  <li key={a.id}><Link to={`/kb/${a.id}`}>{a.title}</Link> <span className="muted small">({Math.round(a.score * 100)}% match)</span></li>
                ))}
              </ul>
            </section>
          )}

          <section aria-labelledby="ai-reply-h" className="ai-reply">
            <h3 id="ai-reply-h">Suggested reply <AiBadge>{AI_LABEL}</AiBadge></h3>
            {decided ? (
              <>
                <p className="notice notice-info" role="status">
                  {result.status === 'REJECTED' ? 'This suggestion was rejected.' : result.status === 'EDITED_APPROVED' ? 'Approved after editing; reply posted.' : 'Approved; reply posted.'}
                </p>
                {result.suggestedReply && <blockquote className="ai-quote">{result.suggestedReply}</blockquote>}
              </>
            ) : result.suggestedReply ? (
              <>
                <Textarea label="Edit before approving" rows={6} value={reply} onChange={(e) => setReply(e.target.value)} disabled={busy} />
                <div className="form-actions">
                  <Button onClick={() => setConfirmOpen(true)} disabled={busy || reply.trim().length === 0}>Approve &amp; post reply</Button>
                  <Button variant="secondary" onClick={() => setRejecting((r) => !r)} disabled={busy}>Reject</Button>
                  <Button variant="ghost" onClick={() => { setResult(null); analyze.mutate(); }} disabled={busy}>Re-analyze</Button>
                </div>
              </>
            ) : <p className="muted">No reply was suggested.</p>}
          </section>

          {rejecting && !decided && (
            <div className="reject-box">
              <Textarea label="Reason for rejecting" rows={2} value={reason} onChange={(e) => setReason(e.target.value)} />
              <Button variant="danger" onClick={() => reject.mutate()} loading={reject.isPending} disabled={reason.trim().length < 3 || reject.isPending}>Confirm reject</Button>
            </div>
          )}
        </div>
      )}

      <ConfirmDialog
        open={confirmOpen}
        title="Post this reply to the customer?"
        message={<p>The reply below will be posted as a <strong>public</strong> comment, visible to the customer. It is AI-generated; please make sure it is correct.</p>}
        confirmLabel="Approve & post"
        busy={approve.isPending}
        onConfirm={() => approve.mutate()}
        onCancel={() => setConfirmOpen(false)}
      />
    </Card>
  );
}
