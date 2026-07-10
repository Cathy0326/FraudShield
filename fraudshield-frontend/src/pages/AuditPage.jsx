import { useState, useEffect } from 'react';
import { getAuditChain, verifyAuditChain } from '../services/api';
import NavBar from '../components/NavBar';
import LoadingSpinner from '../components/LoadingSpinner';

const DECISION_STYLES = {
  CONFIRMED_FRAUD: 'bg-red-900/40 text-red-300 border-red-500/30',
  FALSE_POSITIVE:  'bg-slate-700/40 text-slate-300 border-slate-500/30',
  APPROVED:        'bg-green-900/40 text-green-300 border-green-500/30',
};

/**
 * 审计页 — 合规/安全审计员的视角："谁在何时做了什么决定？记录被动过吗？"
 * Audit Trail: the compliance auditor's view — who decided what, when,
 * and has the record been tampered with?
 *
 * 每条决定是HMAC哈希链上的一环；Verify按钮重算全链，
 * 任何被UPDATE/DELETE过的历史记录都会被精确指出。
 * Every decision is a link in an HMAC hash chain; Verify recomputes the whole
 * chain and pinpoints any record that was edited or deleted after the fact.
 */
export default function AuditPage() {
  const [records,   setRecords]   = useState([]);
  const [loading,   setLoading]   = useState(true);
  const [error,     setError]     = useState('');
  const [verifying, setVerifying] = useState(false);
  const [verdict,   setVerdict]   = useState(null); // result of the last verify run

  useEffect(() => {
    getAuditChain()
      .then(setRecords)
      .catch(() => setError('Failed to load the audit trail.'))
      .finally(() => setLoading(false));
  }, []);

  function handleVerify() {
    setVerifying(true);
    setVerdict(null);
    verifyAuditChain()
      .then(setVerdict)
      .catch(() => setError('Verification request failed.'))
      .finally(() => setVerifying(false));
  }

  const shortHash = (h) => (h ? `${h.slice(0, 8)}…${h.slice(-6)}` : '—');

  return (
    <div className="min-h-screen bg-dark-bg">
      <NavBar />

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
        <div className="flex items-center justify-between flex-wrap gap-3">
          <div>
            <h1 className="text-2xl font-bold text-white">Audit Trail</h1>
            <p className="text-sm text-slate-500 mt-1">
              Every review decision is HMAC-chained to the previous one — editing or
              deleting any historical record breaks every link after it.
            </p>
          </div>
          <button
            onClick={handleVerify}
            disabled={verifying}
            className="px-4 py-2 bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 text-white text-sm font-medium rounded-lg transition-colors"
          >
            {verifying ? 'Verifying…' : '🔒 Verify Chain Integrity'}
          </button>
        </div>

        {verdict && (
          verdict.valid ? (
            <div className="p-4 bg-green-500/10 border border-green-500/30 rounded-xl text-green-300">
              ✅ Chain intact — all {verdict.records} record{verdict.records === 1 ? '' : 's'} verified
              {verdict.keyed ? ' (HMAC-keyed)' : ' (unkeyed SHA-256 mode)'}. No tampering detected.
            </div>
          ) : (
            <div className="p-4 bg-red-500/10 border border-red-500/30 rounded-xl text-red-300">
              ❌ TAMPERING DETECTED — chain breaks at record #{verdict.brokenRecordId}
              {' '}(position {verdict.firstBrokenIndex + 1} of {verdict.records}).
              This record or one before it was modified or deleted after being written.
            </div>
          )
        )}

        {error && (
          <div className="p-4 bg-red-500/10 border border-red-500/30 rounded-xl text-red-400">{error}</div>
        )}

        {loading ? <LoadingSpinner /> : (
          <div className="bg-dark-card border border-dark-border rounded-xl overflow-hidden">
            {records.length === 0 ? (
              <p className="text-slate-500 text-sm text-center py-12">
                No decisions recorded yet — the chain starts with the first review decision.
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-dark-border text-slate-400 text-xs uppercase tracking-wide">
                      {['#', 'Decided At', 'Order ID', 'Decision', 'Reviewer', 'Prev Hash', 'Record Hash'].map(h => (
                        <th key={h} className="px-4 py-3 text-left font-medium">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-dark-border">
                    {records.map(r => (
                      <tr key={r.id} className="hover:bg-dark-bg/40 transition-colors">
                        <td className="px-4 py-3 text-slate-500">{r.id}</td>
                        <td className="px-4 py-3 text-slate-400 whitespace-nowrap">
                          {r.decidedAt ? new Date(r.decidedAt).toLocaleString() : '—'}
                        </td>
                        <td className="px-4 py-3 font-mono text-xs text-slate-300">{r.orderId}</td>
                        <td className="px-4 py-3">
                          <span className={`text-xs font-semibold px-2.5 py-0.5 rounded-full border ${
                            DECISION_STYLES[r.decision] ?? 'bg-slate-700/40 text-slate-300 border-slate-500/30'
                          }`}>
                            {r.decision?.replace('_', ' ')}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-slate-300">{r.reviewer}</td>
                        <td className="px-4 py-3 font-mono text-xs text-slate-600">{shortHash(r.prevHash)}</td>
                        <td className="px-4 py-3 font-mono text-xs text-slate-500">{shortHash(r.recordHash)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
