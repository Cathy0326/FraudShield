import { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { getReviewQueue } from '../services/api';
import NavBar from '../components/NavBar';
import RiskBadge from '../components/RiskBadge';
import LoadingSpinner from '../components/LoadingSpinner';

// 金额档位 — 对应ops的实际分工：小额是卡测试/凑单，大额才值得打电话核实
// Amount brackets mirror how ops actually split work: micro amounts are card
// tests, big tickets are the ones worth a verification phone call
const AMOUNT_BRACKETS = {
  ALL:      { label: 'Any amount',   test: () => true },
  MICRO:    { label: 'Under $10',    test: a => a < 10 },
  SMALL:    { label: '$10 – $100',   test: a => a >= 10 && a < 100 },
  MEDIUM:   { label: '$100 – $500',  test: a => a >= 100 && a < 500 },
  LARGE:    { label: 'Over $500',    test: a => a >= 500 },
};

/**
 * 待审队列 — ops人员的工作入口：检测标记的订单在这里等待人工决定
 * Review queue: the ops entry point. Detection flags orders; humans resolve them here.
 * Rows link to the order detail page where the decision buttons live.
 *
 * 搜索/筛选都在前端做：队列本来就整页加载（几十到几百行），客户端过滤即时响应，
 * 且15秒自动刷新时筛选条件无缝保留。
 * Search/filters are client-side: the queue is already fully loaded (tens to
 * hundreds of rows), so filtering is instant and survives the 15s auto-refresh.
 */
export default function ReviewQueuePage() {
  const navigate = useNavigate();
  const [queue,   setQueue]   = useState([]);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState('');

  // ── 筛选状态 / filter state ──────────────────────────────────────────
  // 初始值来自URL —— ⌘K命令栏用自然语言解析出的筛选就是通过这些参数落地的
  // Seeded from the URL: the ⌘K command bar's natural-language filter lands here
  // via ?risk=…&amount=…&q=… so "high orders over $500" arrives pre-filtered
  const [searchParams] = useSearchParams();
  const [search,       setSearch]       = useState(() => searchParams.get('q') ?? '');
  const [riskFilter,   setRiskFilter]   = useState(() => searchParams.get('risk') ?? 'ALL');
  const [ruleFilter,   setRuleFilter]   = useState(() => searchParams.get('rule') ?? 'ALL');
  const [amountFilter, setAmountFilter] = useState(() =>
    AMOUNT_BRACKETS[searchParams.get('amount')] ? searchParams.get('amount') : 'ALL');

  // 已在本页时用⌘K再下一条筛选 —— navigate不会重挂载，靠此effect把新参数同步进来
  // If ⌘K fires another filter while already on this page, navigate() won't remount,
  // so mirror the incoming params into filter state here
  useEffect(() => {
    setSearch(searchParams.get('q') ?? '');
    setRiskFilter(searchParams.get('risk') ?? 'ALL');
    setRuleFilter(searchParams.get('rule') ?? 'ALL');
    setAmountFilter(AMOUNT_BRACKETS[searchParams.get('amount')] ? searchParams.get('amount') : 'ALL');
  }, [searchParams]);

  const fetchQueue = useCallback(async () => {
    try {
      setQueue(await getReviewQueue());
      setError('');
    } catch {
      setError('Failed to load the review queue.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchQueue();
    const id = setInterval(fetchQueue, 15000);
    return () => clearInterval(id);
  }, [fetchQueue]);

  // 队列中金额合计 — 用金额而不是件数向管理层描述风险敞口
  // Total $ awaiting decision — dollars communicate exposure better than counts
  const amountAtRisk = queue.reduce((sum, e) => sum + (e.amount ?? 0), 0);

  // 规则下拉选项从当前队列动态生成 —— 新规则上线后自动出现，无需改前端
  // Rule dropdown options derive from the live queue - new rules appear automatically
  const ruleOptions = useMemo(
    () => [...new Set(queue.flatMap(e => e.triggeredRules ?? []))].sort(),
    [queue]);

  const filtersActive = search !== '' || riskFilter !== 'ALL'
    || ruleFilter !== 'ALL' || amountFilter !== 'ALL';

  function clearFilters() {
    setSearch('');
    setRiskFilter('ALL');
    setRuleFilter('ALL');
    setAmountFilter('ALL');
  }

  // 搜索覆盖ops手头会有的所有线索：订单号、用户、IP、设备指纹
  // Search spans every identifier an ops person might have in hand:
  // order ID, user ID, IP, device fingerprint
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return queue.filter(e => {
      if (q && ![e.orderId, e.userId, e.ipAddress, e.deviceId]
          .some(v => v && v.toLowerCase().includes(q))) {
        return false;
      }
      if (riskFilter !== 'ALL' && e.riskLevel !== riskFilter) {
        return false;
      }
      if (ruleFilter !== 'ALL' && !(e.triggeredRules ?? []).includes(ruleFilter)) {
        return false;
      }
      return AMOUNT_BRACKETS[amountFilter].test(e.amount ?? 0);
    });
  }, [queue, search, riskFilter, ruleFilter, amountFilter]);

  return (
    <div className="min-h-screen">
      <NavBar />

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
        <div className="flex items-center justify-between">
          <h1 className="text-2xl font-bold text-white">Review Queue</h1>
          {!loading && queue.length > 0 && (
            <div className="text-sm text-slate-400">
              <span className="text-white font-semibold">{queue.length}</span> pending ·
              <span className="text-amber-400 font-semibold ml-1.5">
                ${amountAtRisk.toFixed(2)}
              </span> at risk
            </div>
          )}
        </div>

        {error && (
          <div className="p-4 bg-rose-500/10 border border-rose-500/30 rounded-2xl text-rose-300 flex items-center justify-between">
            <span>{error}</span>
            <button onClick={fetchQueue} className="text-sm underline">Retry</button>
          </div>
        )}

        {/* 搜索 + 多维筛选栏 / search + multi-dimension filter bar */}
        {!loading && queue.length > 0 && (
          <div className="bg-dark-card/80 backdrop-blur-sm shadow-lg shadow-black/20 border border-white/10 rounded-2xl p-4">
            <div className="flex flex-wrap items-center gap-3">
              <input
                type="search"
                value={search}
                onChange={e => setSearch(e.target.value)}
                placeholder="Search order ID, user, IP, device…"
                className="flex-1 min-w-[220px] bg-dark-bg border border-white/10 rounded-lg px-3 py-2 text-sm text-slate-200 placeholder-slate-600 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
              <select
                value={riskFilter}
                onChange={e => setRiskFilter(e.target.value)}
                style={{ colorScheme: 'dark' }}
                className="bg-dark-bg border border-white/10 rounded-lg px-3 py-2 text-sm text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              >
                <option value="ALL">All risk levels</option>
                <option value="HIGH">HIGH</option>
                <option value="MEDIUM">MEDIUM</option>
                <option value="LOW">LOW</option>
              </select>
              <select
                value={ruleFilter}
                onChange={e => setRuleFilter(e.target.value)}
                style={{ colorScheme: 'dark' }}
                className="bg-dark-bg border border-white/10 rounded-lg px-3 py-2 text-sm text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              >
                <option value="ALL">All rules</option>
                {ruleOptions.map(r => <option key={r} value={r}>{r}</option>)}
              </select>
              <select
                value={amountFilter}
                onChange={e => setAmountFilter(e.target.value)}
                style={{ colorScheme: 'dark' }}
                className="bg-dark-bg border border-white/10 rounded-lg px-3 py-2 text-sm text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              >
                {Object.entries(AMOUNT_BRACKETS).map(([key, b]) => (
                  <option key={key} value={key}>{b.label}</option>
                ))}
              </select>
              {filtersActive && (
                <button
                  onClick={clearFilters}
                  className="text-sm px-3 py-2 text-slate-400 hover:text-white border border-white/10 rounded-lg transition-colors"
                >
                  ✕ Clear
                </button>
              )}
            </div>
            {filtersActive && (
              <p className="text-xs text-slate-500 mt-3">
                Showing <span className="text-slate-300 font-semibold">{filtered.length}</span> of{' '}
                {queue.length} pending orders ·{' '}
                <span className="text-amber-400">
                  ${filtered.reduce((s, e) => s + (e.amount ?? 0), 0).toFixed(2)}
                </span>{' '}
                at risk in this view
              </p>
            )}
          </div>
        )}

        {loading ? <LoadingSpinner /> : (
          <div className="bg-dark-card/80 backdrop-blur-sm shadow-lg shadow-black/20 border border-white/10 rounded-2xl overflow-hidden">
            {queue.length === 0 ? (
              <p className="text-slate-500 text-sm text-center py-12">
                Queue is clear — no orders awaiting review. 🎉
              </p>
            ) : filtered.length === 0 ? (
              <div className="text-center py-12">
                <p className="text-slate-500 text-sm">
                  No orders match the current filters.
                </p>
                <button onClick={clearFilters} className="text-indigo-400 text-sm underline mt-2">
                  Clear filters
                </button>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-white/5 text-slate-500 text-xs uppercase tracking-wider">
                      {['Priority', 'Detected', 'Order ID', 'User', 'Amount', 'Risk', 'Rules', ''].map((h, i) => (
                        <th key={i} className="px-4 py-3 text-left font-medium">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-white/5">
                    {filtered.map((e, idx) => (
                      <tr
                        key={e.id}
                        onClick={() => navigate(`/orders/${e.orderId}`)}
                        className={`cursor-pointer transition-colors ${
                          idx < 3 ? 'bg-red-900/10 hover:bg-red-900/20' : 'hover:bg-white/[0.03]'
                        }`}
                      >
                        <td className="px-4 py-3 whitespace-nowrap">
                          {/* 期望损失 = 风险分×金额，队列按此降序 / expected loss drives the sort */}
                          <span className={`text-xs font-semibold ${
                            idx < 3 ? 'text-red-300' : 'text-slate-500'
                          }`}>
                            {idx < 3 && '🔥 '}${((e.riskScore ?? 0) * (e.amount ?? 0)).toFixed(0)}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-slate-400 whitespace-nowrap">
                          {e.detectedAt ? new Date(e.detectedAt).toLocaleString() : '—'}
                        </td>
                        <td className="px-4 py-3 font-mono text-xs text-indigo-300">{e.orderId}</td>
                        <td className="px-4 py-3 text-slate-300">{e.userId}</td>
                        <td className="px-4 py-3 text-slate-300">${e.amount?.toFixed(2)}</td>
                        <td className="px-4 py-3"><RiskBadge riskLevel={e.riskLevel} /></td>
                        <td className="px-4 py-3 text-slate-400 text-xs">{e.triggeredRules?.join(', ')}</td>
                        <td className="px-4 py-3 text-indigo-400 text-xs whitespace-nowrap">Review →</td>
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
