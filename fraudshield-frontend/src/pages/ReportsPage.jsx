import { useState, useEffect } from 'react';
import { getEventsByDateRange, getRulePrecision, getFinancialImpact } from '../services/api';
import NavBar from '../components/NavBar';
import RiskBadge from '../components/RiskBadge';
import LoadingSpinner from '../components/LoadingSpinner';

// CSV导出：纯前端实现，无需后端
// CSV export: pure frontend — create a Blob and trigger a download link
function exportCsv(events) {
  const header = 'OrderId,UserId,IPAddress,Amount,RiskLevel,RiskScore,Rules,DetectedAt';
  const rows   = events.map(e =>
    [e.orderId, e.userId, e.ipAddress, e.amount, e.riskLevel,
     e.riskScore, e.triggeredRules?.join('|'), e.detectedAt].join(',')
  );
  const csv  = [header, ...rows].join('\n');
  const blob = new Blob([csv], { type: 'text/csv' });
  const url  = URL.createObjectURL(blob);
  const a    = document.createElement('a');
  a.href     = url;
  a.download = `risk-report-${new Date().toISOString().split('T')[0]}.csv`;
  a.click();
  URL.revokeObjectURL(url); // 释放内存 / free the temporary URL
}

// yyyy-MM-dd（本地时区）— input[type=date]的取值格式 / local-date string for date inputs
function isoDate(d) {
  const tzAdjusted = new Date(d.getTime() - d.getTimezoneOffset() * 60000);
  return tzAdjusted.toISOString().split('T')[0];
}

const usd = (n) => (n < 0 ? '-$' : '$') + Math.abs(Math.round(n ?? 0)).toLocaleString();

// ── 规则健康分级 —— GMC式"状态框架"：把每条规则判成一个可执行状态 + 一句该做什么
//    Rule health grading — GMC's status framework: each rule becomes one actionable
//    state plus a sentence on what to do about it. Ordering (worst first) is deliberate:
//    a fraud lead should see the rule bleeding the most false alarms at the top.
function gradeRule(r) {
  const falseAlarms = (r.falsePositive ?? 0) + (r.approved ?? 0);
  if (r.reviewedHits === 0 || r.precision == null) {
    return {
      key: 'UNGRADED', label: 'UNGRADED', c: '#64748b', falseAlarms,
      soWhat: `${r.totalHits} hits, none reviewed yet — label a few to grade this rule.`,
      rank: 2,   // 中间：既不健康也不明确有害 / neither proven good nor bad
    };
  }
  if (r.precision >= 80) {
    return {
      key: 'HEALTHY', label: 'HEALTHY', c: '#34d399', falseAlarms,
      soWhat: `Firing true ${r.precision.toFixed(0)}% of the time — keep it as is.`,
      rank: 3,
    };
  }
  if (r.precision >= 50) {
    return {
      key: 'WATCH', label: 'WATCH', c: '#fb923c', falseAlarms,
      soWhat: `${falseAlarms} of ${r.reviewedHits} calls were false alarms — tighten a condition.`,
      rank: 1,
    };
  }
  return {
    key: 'NOISY', label: 'NOISY', c: '#f43f5e', falseAlarms,
    soWhat: `Mostly wrong (${r.precision.toFixed(0)}% precision) — lower its weight or add a guard.`,
    rank: 0,   // 最优先：噪声规则拖垮整个队列的信噪比 / noisiest first
  };
}

function RuleHealthCard({ r }) {
  const g = gradeRule(r);
  const pct = r.precision;
  return (
    <div className="rise-in relative overflow-hidden rounded-2xl border border-white/5
                    bg-gradient-to-br from-dark-card to-[#141d30] shadow-lg shadow-black/20
                    p-5 transition-all duration-200 hover:-translate-y-0.5">
      <span className="absolute left-0 top-0 h-full w-1" style={{ background: g.c }} />
      <div className="flex items-start justify-between gap-2">
        <p className="font-mono text-sm text-slate-200 leading-tight">{r.rule}</p>
        <span className="shrink-0 text-[10px] font-bold tracking-wider px-2 py-0.5 rounded-full"
              style={{ color: g.c, background: `${g.c}1a`, border: `1px solid ${g.c}33` }}>
          {g.label}
        </span>
      </div>

      <div className="mt-4 flex items-end gap-2">
        <span className="text-3xl font-bold tabular-nums" style={{ color: g.c }}>
          {pct == null ? '—' : `${pct.toFixed(0)}%`}
        </span>
        <span className="text-xs text-slate-500 mb-1">precision</span>
      </div>
      <div className="mt-2 h-1.5 w-full rounded-full bg-white/5 overflow-hidden">
        <div className="h-full rounded-full transition-all"
             style={{ width: `${pct ?? 0}%`, background: g.c }} />
      </div>

      <div className="mt-4 grid grid-cols-3 gap-2 text-center">
        {[
          { k: 'Hits',      v: r.totalHits,      c: 'text-slate-300' },
          { k: 'Confirmed', v: r.confirmedFraud, c: 'text-emerald-300' },
          { k: 'Wrong',     v: g.falseAlarms,    c: 'text-rose-300' },
        ].map(s => (
          <div key={s.k}>
            <p className={`text-lg font-semibold tabular-nums ${s.c}`}>{s.v}</p>
            <p className="text-[10px] uppercase tracking-wider text-slate-500">{s.k}</p>
          </div>
        ))}
      </div>

      <p className="mt-4 text-xs leading-snug text-slate-400 border-t border-white/5 pt-3">{g.soWhat}</p>
    </div>
  );
}

// ── $瀑布：拦截额(+) → 误杀额(−) → 净保护 —— Snowflake式"成本抵价值"一图看清
//    Money waterfall: intercepted (+) minus false-kill (−) equals net protected.
function Waterfall({ impact }) {
  const intercepted = impact.interceptedAmount ?? 0;
  const falseKill   = impact.falsePositiveAmount ?? 0;
  const net         = intercepted - falseKill;
  const scale = Math.max(intercepted, 1);
  const bar = (v) => `${Math.min(100, (Math.abs(v) / scale) * 100)}%`;
  const rows = [
    { label: 'Fraud intercepted', sub: `${impact.interceptedCount ?? 0} confirmed`,      v: intercepted, c: '#34d399', sign: '+' },
    { label: 'Revenue wrongly killed', sub: `${impact.falsePositiveCount ?? 0} false positives`, v: -falseKill,  c: '#f43f5e', sign: '−' },
  ];
  return (
    <div className="space-y-3">
      {rows.map(row => (
        <div key={row.label} className="flex items-center gap-3">
          <div className="w-44 shrink-0">
            <p className="text-sm text-slate-200">{row.label}</p>
            <p className="text-[11px] text-slate-500">{row.sub}</p>
          </div>
          <div className="flex-1 h-7 rounded-lg bg-white/5 overflow-hidden">
            <div className="h-full rounded-lg flex items-center justify-end px-2"
                 style={{ width: bar(row.v), background: `${row.c}33`, borderRight: `2px solid ${row.c}` }}>
              <span className="text-xs font-semibold tabular-nums" style={{ color: row.c }}>
                {row.sign}{usd(row.v)}
              </span>
            </div>
          </div>
        </div>
      ))}
      <div className="flex items-center gap-3 border-t border-white/10 pt-3">
        <div className="w-44 shrink-0">
          <p className="text-sm font-semibold text-white">Net protected</p>
          <p className="text-[11px] text-slate-500">
            {impact.interceptToFalseKillRatio == null
              ? 'nothing wrongly blocked yet'
              : `${impact.interceptToFalseKillRatio.toFixed(1)}× caught per $1 lost`}
          </p>
        </div>
        <p className={`text-2xl font-bold tabular-nums ${net >= 0 ? 'text-emerald-300' : 'text-rose-300'}`}>
          {usd(net)}
        </p>
      </div>
    </div>
  );
}

function Panel({ title, subtitle, children, className = '' }) {
  return (
    <div className={`rise-in rounded-2xl border border-white/5 bg-dark-card/80 backdrop-blur-sm
                     shadow-lg shadow-black/20 ${className}`}>
      <div className="px-5 pt-5">
        <h2 className="text-base font-semibold text-white">{title}</h2>
        {subtitle && <p className="text-xs text-slate-500 mt-0.5">{subtitle}</p>}
      </div>
      <div className="p-5 pt-4">{children}</div>
    </div>
  );
}

export default function ReportsPage() {
  // 默认最近7天 —— 让日期选择器一打开就"能用"，而不是两个空框
  // Default to the last 7 days so the pickers work out of the box, not two empty boxes
  const [startDate, setStartDate] = useState(() =>
    isoDate(new Date(Date.now() - 7 * 24 * 60 * 60 * 1000)));
  const [endDate,   setEndDate]   = useState(() => isoDate(new Date()));
  const [events,    setEvents]    = useState([]);
  const [loading,   setLoading]   = useState(false);
  const [error,     setError]     = useState('');
  const [generated, setGenerated] = useState(false);
  const [rulePrecision, setRulePrecision] = useState([]);
  const [impact, setImpact] = useState(null);

  // 规则精度独立于日期报表加载 —— 它反映的是全部历史标注
  // Rule precision loads independently of the date report — it reflects all labels to date
  useEffect(() => {
    getRulePrecision().then(setRulePrecision).catch(() => {});
    getFinancialImpact().then(setImpact).catch(() => {});
  }, []);

  const handleGenerate = async () => {
    if (!startDate || !endDate) {
      setError('Please select both a From and a To date.');
      return;
    }
    if (startDate > endDate) {
      setError('The From date must not be after the To date.');
      return;
    }
    setLoading(true);
    setError('');
    try {
      // 日期真正生效：后端 /range 接口按 detectedAt 过滤（两端日期都包含）
      // The dates are honored now — the /range endpoint filters by detectedAt,
      // both endpoint days inclusive
      const data = await getEventsByDateRange(startDate, endDate);
      setEvents(data);
      setGenerated(true);
    } catch {
      setError('Failed to generate report. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const high   = events.filter(e => e.riskLevel === 'HIGH').length;
  const medium = events.filter(e => e.riskLevel === 'MEDIUM').length;

  // 最需要行动的规则排前：NOISY → WATCH → UNGRADED → HEALTHY，同级按误报数降序
  // Sort so the rule most in need of a decision leads; ties broken by false-alarm volume
  const rulesRanked = [...rulePrecision].sort((a, b) => {
    const ga = gradeRule(a), gb = gradeRule(b);
    return ga.rank - gb.rank || gb.falseAlarms - ga.falseAlarms;
  });
  const noisyCount = rulesRanked.filter(r => gradeRule(r).key === 'NOISY').length;

  return (
    <div className="min-h-screen">
      <NavBar />
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-6">
        <h1 className="text-2xl font-bold text-white tracking-tight">Reports</h1>

        {/* $ Waterfall — 财务视角一行叙事 / finance's one-line narrative */}
        {impact && (
          <Panel title="Financial Impact" subtitle="are we protecting more than we're costing?">
            <Waterfall impact={impact} />
          </Panel>
        )}

        {/* Rule Health Board — GMC式状态卡网格，最吵的规则排最前 / GMC status grid, noisiest first */}
        <Panel
          title="Rule Health Board"
          subtitle={
            rulesRanked.length === 0
              ? 'graded from review decisions'
              : noisyCount > 0
                ? `${noisyCount} rule${noisyCount > 1 ? 's' : ''} running noisy — sorted worst-first`
                : 'graded from review decisions · sorted worst-first'
          }
        >
          {rulesRanked.length === 0 ? (
            <p className="text-slate-500 text-sm text-center py-8">
              No rule hits recorded yet — nothing to grade.
            </p>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
              {rulesRanked.map(r => <RuleHealthCard key={r.rule} r={r} />)}
            </div>
          )}
        </Panel>

        {/* Date-range report */}
        <Panel title="Date Range" subtitle="pull risk events for a window, then export">
          <div className="flex flex-wrap items-end gap-4">
            {/* colorScheme:'dark' —— 否则Chrome把日历图标画成黑色，深色背景上不可见 */}
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">From</label>
              <input type="date" value={startDate} max={endDate || undefined}
                onChange={e => setStartDate(e.target.value)}
                style={{ colorScheme: 'dark' }}
                className="bg-dark-bg border border-white/10 rounded-lg px-3 py-2 text-slate-200 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
            </div>
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">To</label>
              <input type="date" value={endDate} min={startDate || undefined}
                onChange={e => setEndDate(e.target.value)}
                style={{ colorScheme: 'dark' }}
                className="bg-dark-bg border border-white/10 rounded-lg px-3 py-2 text-slate-200 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
            </div>
            <button onClick={handleGenerate} disabled={loading}
              className="px-5 py-2 text-white text-sm font-medium rounded-lg bg-gradient-to-r from-indigo-500 to-violet-500
                         shadow-lg shadow-indigo-900/30 hover:from-indigo-400 hover:to-violet-400 disabled:opacity-50 transition-all active:scale-95">
              {loading ? 'Generating…' : 'Generate Report'}
            </button>
            {generated && events.length > 0 && (
              <button onClick={() => exportCsv(events)}
                className="px-5 py-2 bg-emerald-700/40 hover:bg-emerald-700/60 text-emerald-300 border border-emerald-500/30 text-sm font-medium rounded-lg transition-colors">
                ⬇ Export CSV
              </button>
            )}
          </div>
        </Panel>

        {error && (
          <div className="p-4 bg-rose-500/10 border border-rose-500/30 rounded-xl text-rose-300">{error}</div>
        )}

        {loading ? <LoadingSpinner /> : generated && (
          <>
            {/* Summary cards */}
            <div className="grid grid-cols-3 gap-4">
              {[
                { label: 'Total in Range', value: events.length, accent: '#818cf8' },
                { label: 'High Risk',      value: high,   accent: '#f43f5e' },
                { label: 'Medium Risk',    value: medium, accent: '#fb923c' },
              ].map(c => (
                <div key={c.label}
                     className="relative overflow-hidden rounded-2xl border border-white/5 bg-gradient-to-br from-dark-card to-[#141d30] p-4">
                  <span className="absolute left-0 top-0 h-full w-1" style={{ background: c.accent }} />
                  <p className="text-xs text-slate-400">{c.label}</p>
                  <p className="text-2xl font-bold text-white mt-1 tabular-nums">{c.value}</p>
                </div>
              ))}
            </div>

            {/* Results table */}
            <Panel title={`Report Results (${events.length} events)`} className="overflow-hidden">
              {events.length === 0 ? (
                <p className="text-slate-500 text-center py-10 text-sm">No events found</p>
              ) : (
                <div className="overflow-x-auto -mx-5 -mb-5">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-white/5 text-slate-500 text-xs uppercase tracking-wider">
                        {['Time','Order ID','User','IP','Amount','Risk Level','Rules'].map(h => (
                          <th key={h} className="px-5 py-3 text-left font-medium">{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-white/5">
                      {events.map(e => (
                        <tr key={e.id} className="hover:bg-white/[0.03] transition-colors">
                          <td className="px-5 py-3 text-slate-400 whitespace-nowrap">
                            {e.detectedAt ? new Date(e.detectedAt).toLocaleString() : '—'}
                          </td>
                          <td className="px-5 py-3 font-mono text-xs text-indigo-300">{e.orderId}</td>
                          <td className="px-5 py-3 text-slate-300">{e.userId}</td>
                          <td className="px-5 py-3 font-mono text-xs text-slate-400">{e.ipAddress}</td>
                          <td className="px-5 py-3 text-slate-200 tabular-nums">${e.amount?.toFixed(2)}</td>
                          <td className="px-5 py-3"><RiskBadge riskLevel={e.riskLevel} /></td>
                          <td className="px-5 py-3 text-slate-400 text-xs">{e.triggeredRules?.join(', ')}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Panel>
          </>
        )}
      </div>
    </div>
  );
}
