import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { getAuditChain, verifyAuditChain } from '../services/api';
import NavBar from '../components/NavBar';
import LoadingSpinner from '../components/LoadingSpinner';

const DECISION_STYLES = {
  CONFIRMED_FRAUD: 'bg-red-900/40 text-red-300 border-red-500/30',
  FALSE_POSITIVE:  'bg-slate-700/40 text-slate-300 border-slate-500/30',
  APPROVED:        'bg-green-900/40 text-green-300 border-green-500/30',
};

/**
 * 审计页 — 合规/安全审计员的工作台，而不是哈希倾倒场
 * Audit Trail: a compliance workbench, not a hash dump.
 *
 * 回答审计员真正会问的三个问题：
 *   1. 记录可信吗？→ 页面加载即自动校验全链，结论放在最顶部
 *   2. 谁在何时做了什么决定？→ 决定统计 + 按订单/审核人搜索、按决定类型筛选
 *   3. 这条记录怎么用？→ 每行直达订单详情和争议证据包（链的实际用途）
 * Answers the three questions an auditor actually asks:
 *   1. Can I trust these records? - the chain auto-verifies on load, verdict on top
 *   2. Who decided what, when? - decision stats + search by order/reviewer,
 *      filter by decision type
 *   3. What do I do with a record? - every row links to the order detail and its
 *      dispute evidence package, which is what the chain exists to back up
 */
export default function AuditPage() {
  const navigate = useNavigate();
  const [records,   setRecords]   = useState([]);
  const [loading,   setLoading]   = useState(true);
  const [error,     setError]     = useState('');
  const [verifying, setVerifying] = useState(false);
  const [verdict,   setVerdict]   = useState(null);

  // ── 筛选状态 / filter state ──────────────────────────────────────────
  const [search,         setSearch]         = useState('');
  const [decisionFilter, setDecisionFilter] = useState('ALL');

  function runVerify() {
    setVerifying(true);
    return verifyAuditChain()
      .then(setVerdict)
      .catch(() => setError('Verification request failed.'))
      .finally(() => setVerifying(false));
  }

  useEffect(() => {
    // 完整性结论是本页存在的意义 —— 加载即校验，不该藏在按钮后面
    // The integrity verdict is the point of this page - verify on load,
    // don't hide it behind a button
    Promise.all([
      getAuditChain().then(setRecords).catch(() => setError('Failed to load the audit trail.')),
      runVerify(),
    ]).finally(() => setLoading(false));
  }, []);

  // 决定统计 — 合规视角的"谁在做什么"概览 / decision stats: the compliance overview
  const stats = useMemo(() => ({
    total:     records.length,
    fraud:     records.filter(r => r.decision === 'CONFIRMED_FRAUD').length,
    falsePos:  records.filter(r => r.decision === 'FALSE_POSITIVE').length,
    approved:  records.filter(r => r.decision === 'APPROVED').length,
    reviewers: new Set(records.map(r => r.reviewer).filter(Boolean)).size,
  }), [records]);

  const filtersActive = search !== '' || decisionFilter !== 'ALL';

  // 显示新→旧（审计员先看最近的），链本身的旧→新顺序只影响哈希计算，与展示无关
  // Display newest first (auditors start from recent activity); the chain's
  // oldest-first ordering only matters for hashing, not for reading
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return [...records]
      .reverse()
      .filter(r => {
        if (q && ![r.orderId, r.reviewer]
            .some(v => v && v.toLowerCase().includes(q))) {
          return false;
        }
        return decisionFilter === 'ALL' || r.decision === decisionFilter;
      });
  }, [records, search, decisionFilter]);

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
              deleting any historical record breaks every link after it. These records
              back the dispute evidence packages submitted to card networks.
            </p>
          </div>
          <button
            onClick={runVerify}
            disabled={verifying}
            className="px-4 py-2 bg-slate-700 hover:bg-slate-600 disabled:opacity-50 text-white text-sm font-medium rounded-lg transition-colors"
          >
            {verifying ? 'Verifying…' : '↻ Re-verify'}
          </button>
        </div>

        {/* 完整性状态 — 页面加载即出结论 / integrity verdict, shown from page load */}
        {verdict && (
          verdict.valid ? (
            <div className="p-4 bg-green-500/10 border border-green-500/30 rounded-xl text-green-300">
              ✅ Chain verified intact — all {verdict.records} record{verdict.records === 1 ? '' : 's'} check out
              {verdict.keyed ? ' (HMAC-keyed)' : ' (unkeyed SHA-256 mode)'}. Evidence packages
              generated from these records carry a valid integrity attestation.
            </div>
          ) : (
            <div className="p-4 bg-red-500/10 border border-red-500/30 rounded-xl text-red-300">
              ❌ TAMPERING DETECTED — chain breaks at record #{verdict.brokenRecordId}
              {' '}(position {verdict.firstBrokenIndex + 1} of {verdict.records}).
              This record or one before it was modified or deleted after being written.
              Evidence packages will report the failed check until this is investigated.
            </div>
          )
        )}

        {error && (
          <div className="p-4 bg-red-500/10 border border-red-500/30 rounded-xl text-red-400">{error}</div>
        )}

        {/* 决定统计 / decision stats */}
        {!loading && records.length > 0 && (
          <div className="grid grid-cols-2 sm:grid-cols-5 gap-4">
            {[
              ['Decisions',       stats.total,     'border-dark-border',   'text-white'],
              ['Confirmed Fraud', stats.fraud,     'border-red-500/30',    'text-red-300'],
              ['False Positives', stats.falsePos,  'border-slate-500/30',  'text-slate-300'],
              ['Approved',        stats.approved,  'border-green-500/30',  'text-green-300'],
              ['Reviewers',       stats.reviewers, 'border-indigo-500/30', 'text-indigo-300'],
            ].map(([label, value, border, color]) => (
              <div key={label} className={`bg-dark-card border ${border} rounded-xl p-4`}>
                <p className="text-xs text-slate-400">{label}</p>
                <p className={`text-2xl font-bold mt-1 ${color}`}>{value}</p>
              </div>
            ))}
          </div>
        )}

        {/* 搜索 + 决定类型筛选 / search + decision filter */}
        {!loading && records.length > 0 && (
          <div className="bg-dark-card border border-dark-border rounded-xl p-4 flex flex-wrap items-center gap-3">
            <input
              type="search"
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="Search order ID or reviewer…"
              className="flex-1 min-w-[220px] bg-dark-bg border border-dark-border rounded-lg px-3 py-2 text-sm text-slate-200 placeholder-slate-600 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <select
              value={decisionFilter}
              onChange={e => setDecisionFilter(e.target.value)}
              style={{ colorScheme: 'dark' }}
              className="bg-dark-bg border border-dark-border rounded-lg px-3 py-2 text-sm text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            >
              <option value="ALL">All decisions</option>
              <option value="CONFIRMED_FRAUD">Confirmed fraud</option>
              <option value="FALSE_POSITIVE">False positive</option>
              <option value="APPROVED">Approved</option>
            </select>
            {filtersActive && (
              <>
                <button
                  onClick={() => { setSearch(''); setDecisionFilter('ALL'); }}
                  className="text-sm px-3 py-2 text-slate-400 hover:text-white border border-dark-border rounded-lg transition-colors"
                >
                  ✕ Clear
                </button>
                <span className="text-xs text-slate-500">
                  {filtered.length} of {records.length} records
                </span>
              </>
            )}
          </div>
        )}

        {loading ? <LoadingSpinner /> : (
          <div className="bg-dark-card border border-dark-border rounded-xl overflow-hidden">
            {records.length === 0 ? (
              <p className="text-slate-500 text-sm text-center py-12">
                No decisions recorded yet — the chain starts with the first review decision.
              </p>
            ) : filtered.length === 0 ? (
              <div className="text-center py-12">
                <p className="text-slate-500 text-sm">No records match the current filters.</p>
                <button
                  onClick={() => { setSearch(''); setDecisionFilter('ALL'); }}
                  className="text-indigo-400 text-sm underline mt-2"
                >
                  Clear filters
                </button>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-dark-border text-slate-400 text-xs uppercase tracking-wide">
                      {['#', 'Decided At', 'Order ID', 'Decision', 'Reviewer', 'Record Hash', ''].map((h, i) => (
                        <th key={i} className="px-4 py-3 text-left font-medium">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-dark-border">
                    {filtered.map(r => (
                      <tr key={r.id} className="hover:bg-dark-bg/40 transition-colors">
                        <td className="px-4 py-3 text-slate-500">{r.id}</td>
                        <td className="px-4 py-3 text-slate-400 whitespace-nowrap">
                          {r.decidedAt ? new Date(r.decidedAt).toLocaleString() : '—'}
                        </td>
                        <td className="px-4 py-3">
                          <button
                            onClick={() => navigate(`/orders/${r.orderId}`)}
                            className="font-mono text-xs text-indigo-300 hover:text-indigo-200 hover:underline"
                          >
                            {r.orderId}
                          </button>
                        </td>
                        <td className="px-4 py-3">
                          <span className={`text-xs font-semibold px-2.5 py-0.5 rounded-full border ${
                            DECISION_STYLES[r.decision] ?? 'bg-slate-700/40 text-slate-300 border-slate-500/30'
                          }`}>
                            {r.decision?.replace('_', ' ')}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-slate-300">{r.reviewer}</td>
                        {/* title=完整哈希，悬停可查、可复制 / full hash on hover via title */}
                        <td className="px-4 py-3 font-mono text-xs text-slate-500" title={r.recordHash}>
                          {shortHash(r.recordHash)}
                        </td>
                        <td className="px-4 py-3 whitespace-nowrap">
                          {/* 链的实际用途：为争议证据包背书 —— 一键直达
                              The chain's real job is backing evidence packages - link straight there */}
                          <button
                            onClick={() => navigate(`/orders/${r.orderId}/evidence`)}
                            className="text-xs px-2.5 py-1 bg-slate-700/60 hover:bg-slate-600 text-slate-200 rounded-md transition-colors"
                          >
                            📄 Evidence
                          </button>
                        </td>
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
