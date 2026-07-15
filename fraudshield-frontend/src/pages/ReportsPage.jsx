import { useState, useEffect } from 'react';
import { getEventsByDateRange, getRulePrecision, getFinancialImpact, getDashboardStats,
  getRuleConfig, updateRuleConfig } from '../services/api';
import { useAuth } from '../context/AuthContext';
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

// 精度周环比箭头 —— precision"涨"是好事：↑绿 ↓红（与风险KPI相反）。
// 阈值±0.5pt内视为持平，避免噪声抖动。null=两个窗口凑不齐可比数据。
// Week-over-week precision arrow. For precision, up is GOOD: green up, red down
// (opposite to risk KPIs). Within ±0.5pt reads as flat to avoid noisy flicker.
// null = not enough data in both windows to draw a trend.
function TrendArrow({ delta }) {
  if (delta == null) {
    return <span className="ml-auto mb-1 text-[11px] text-slate-600" title="Need reviewed hits in both weeks">no trend</span>;
  }
  const flat = Math.abs(delta) < 0.5;
  const up = delta > 0;
  const c = flat ? '#64748b' : up ? '#34d399' : '#f43f5e';
  const glyph = flat ? '→' : up ? '▲' : '▼';
  return (
    <span className="ml-auto mb-1 inline-flex items-center gap-1 text-[11px] font-semibold tabular-nums"
          style={{ color: c }} title="Precision change vs the prior 7 days">
      {glyph} {flat ? '0' : `${Math.abs(delta).toFixed(1)}`} pts
      <span className="text-slate-600 font-normal">WoW</span>
    </span>
  );
}

function RuleHealthCard({ r, cfg, isAdmin, busy, onUpdate }) {
  const g = gradeRule(r);
  const pct = r.precision;
  // 拖动时的本地权重值，松手才提交 —— 避免拖拽过程狂发请求
  // Local slider value while dragging; commit on release so we don't spam requests
  const [drag, setDrag] = useState(null);
  const weight = drag ?? cfg?.effectiveWeight ?? 1;
  const disabled = cfg && !cfg.enabled;
  return (
    <div className={`rise-in relative overflow-hidden rounded-2xl border border-white/5
                    bg-gradient-to-br from-dark-card to-[#141d30] shadow-lg shadow-black/20
                    p-5 transition-all duration-200 hover:-translate-y-0.5 ${disabled ? 'opacity-60' : ''}`}>
      <span className="absolute left-0 top-0 h-full w-1"
            style={{ background: disabled ? '#475569' : g.c }} />
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
        <TrendArrow delta={r.precisionDelta} />
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

      {/* 调优控件 —— 诊断就地变操作：调权重 / 停用规则（仅ADMIN可改，其余只读）
          Tuning controls — turn the diagnosis into action right here: set weight /
          disable the rule. ADMIN can change; everyone else sees it read-only. */}
      {cfg && (
        <div className="mt-3 border-t border-white/5 pt-3">
          <div className="flex items-center justify-between">
            <span className="text-[11px] uppercase tracking-wider text-slate-500">Weight in scoring</span>
            <span className="font-mono text-xs text-slate-300">
              {weight.toFixed(2)}
              <span className="text-slate-600"> · {cfg.weightOverride != null ? 'manual' : 'auto'}</span>
            </span>
          </div>
          {isAdmin ? (
            <div className="mt-2 flex items-center gap-2">
              <input
                type="range" min="0" max="1" step="0.05" value={weight}
                disabled={busy || disabled}
                onChange={e => setDrag(parseFloat(e.target.value))}
                onPointerUp={() => { if (drag != null) { onUpdate(r.rule, { weight: drag }); setDrag(null); } }}
                onKeyUp={() => { if (drag != null) { onUpdate(r.rule, { weight: drag }); setDrag(null); } }}
                className="flex-1 accent-indigo-400 disabled:opacity-40"
              />
              <button
                onClick={() => onUpdate(r.rule, { weight: null })}
                disabled={busy || cfg.weightOverride == null}
                title="Reset to the precision-derived auto weight"
                className="text-[11px] px-2 py-0.5 rounded border border-white/10 text-slate-400 hover:text-white disabled:opacity-40 transition-colors">
                auto
              </button>
              <button
                onClick={() => onUpdate(r.rule, { enabled: !cfg.enabled })}
                disabled={busy}
                className={`text-[11px] px-2 py-0.5 rounded border transition-colors ${
                  cfg.enabled ? 'border-emerald-500/30 text-emerald-300 hover:bg-emerald-500/10'
                              : 'border-rose-500/30 text-rose-300 hover:bg-rose-500/10'}`}>
                {cfg.enabled ? '● On' : '○ Off'}
              </button>
            </div>
          ) : (
            <p className="mt-1.5 text-[11px] text-slate-500">
              {cfg.enabled ? 'Enabled' : 'Disabled'} · an admin can tune weight and toggle this rule
            </p>
          )}
        </div>
      )}
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

// ── 检测→审核→定案 漏斗 —— PostHog式转化漏斗，回答"案子卡在哪、漏在哪"
//    Detection → Review → Confirmed funnel: where do cases pile up, where do they leak?
//    Flagged = persisted risk events; Reviewed/Confirmed come from review decisions.
function PipelineFunnel({ flagged, impact }) {
  const reviewed  = (impact.interceptedCount ?? 0) + (impact.falsePositiveCount ?? 0) + (impact.approvedCount ?? 0);
  const confirmed = impact.interceptedCount ?? 0;
  const released  = (impact.falsePositiveCount ?? 0) + (impact.approvedCount ?? 0);
  // 未定案积压 = 已标记但还没审 —— 漏斗最该关注的"漏点"
  // Backlog = flagged but not yet reviewed; the leak the funnel exists to surface.
  const pending   = Math.max(0, flagged - reviewed);

  const stages = [
    { label: 'Flagged',        n: flagged,   c: '#818cf8', note: 'risk events detected' },
    { label: 'Reviewed',       n: reviewed,  c: '#38bdf8', note: `${flagged ? Math.round((reviewed / flagged) * 100) : 0}% of flagged decided` },
    { label: 'Confirmed fraud', n: confirmed, c: '#f43f5e', note: `${reviewed ? Math.round((confirmed / reviewed) * 100) : 0}% of reviewed were real` },
  ];
  const top = Math.max(flagged, 1);

  const soWhat = pending > 0
    ? `${pending} flagged order${pending > 1 ? 's' : ''} still awaiting a decision — the backlog is where risk hides.`
    : reviewed === 0
      ? 'Nothing reviewed yet — decisions feed every rate on this page.'
      : `Queue is current: every flagged order has a decision, ${released} released as good.`;

  return (
    <div className="space-y-3">
      {stages.map((s, i) => (
        <div key={s.label} className="flex items-center gap-3">
          <div className="w-32 shrink-0 text-right">
            <p className="text-sm text-slate-200">{s.label}</p>
            <p className="text-[11px] text-slate-500">{s.note}</p>
          </div>
          <div className="flex-1 h-9 rounded-lg bg-white/5 overflow-hidden">
            <div className="h-full rounded-lg flex items-center px-3 transition-all"
                 style={{ width: `${Math.max(6, (s.n / top) * 100)}%`,
                          background: `linear-gradient(90deg, ${s.c}40, ${s.c}20)`,
                          borderLeft: `3px solid ${s.c}` }}>
              <span className="text-sm font-bold tabular-nums text-white">{s.n.toLocaleString()}</span>
            </div>
          </div>
          {i < stages.length - 1 && (
            <span className="w-10 shrink-0 text-center text-[11px] text-slate-500 tabular-nums">
              ↓{stages[i].n ? Math.round((stages[i + 1].n / stages[i].n) * 100) : 0}%
            </span>
          )}
          {i === stages.length - 1 && <span className="w-10 shrink-0" />}
        </div>
      ))}
      {pending > 0 && (
        <div className="flex items-center gap-3">
          <div className="w-32 shrink-0 text-right">
            <p className="text-sm text-amber-300">Backlog</p>
            <p className="text-[11px] text-slate-500">flagged, not yet reviewed</p>
          </div>
          <div className="flex-1 h-7 rounded-lg bg-white/5 overflow-hidden">
            <div className="h-full rounded-lg flex items-center px-3"
                 style={{ width: `${Math.max(6, (pending / top) * 100)}%`,
                          background: '#fb923c22', borderLeft: '3px solid #fb923c' }}>
              <span className="text-xs font-semibold tabular-nums text-amber-300">{pending.toLocaleString()}</span>
            </div>
          </div>
          <span className="w-10 shrink-0" />
        </div>
      )}
      <p className="text-xs leading-snug text-slate-400 border-t border-white/5 pt-3">{soWhat}</p>
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
  const [stats, setStats] = useState(null);
  const [ruleCfg, setRuleCfg] = useState({});   // keyed by rule name
  const [cfgBusy, setCfgBusy] = useState(false);

  const { user } = useAuth();
  const isAdmin = (user?.role ?? '').includes('ADMIN');

  const loadRuleConfig = () =>
    getRuleConfig()
      .then(list => setRuleCfg(Object.fromEntries(list.map(c => [c.rule, c]))))
      .catch(() => {});

  // 规则精度/财务/概览独立于日期报表加载 —— 都反映全部历史，不受日期窗口约束
  // Precision, finance, and overview load independently of the date report — all
  // reflect the full history, not the selected window
  useEffect(() => {
    getRulePrecision().then(setRulePrecision).catch(() => {});
    getFinancialImpact().then(setImpact).catch(() => {});
    getDashboardStats().then(setStats).catch(() => {});
    loadRuleConfig();
  }, []);

  // 调优提交：写回后端并用返回的最新配置就地更新那张卡 / commit a tuning change,
  // then patch that one card from the server's fresh config
  const handleRuleUpdate = async (rule, body) => {
    setCfgBusy(true);
    try {
      const updated = await updateRuleConfig(rule, body);
      setRuleCfg(prev => ({ ...prev, [rule]: updated }));
    } catch { /* likely a non-admin / 403 — ignore, UI stays as-is */ }
    finally { setCfgBusy(false); }
  };

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

        {/* 漏斗 + $瀑布：管道健康 与 财务叙事并排 / pipeline health beside the money story */}
        {impact && (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <Panel title="Detection Pipeline" subtitle="detected → reviewed → confirmed">
              <PipelineFunnel
                flagged={(stats?.totalOrders ?? 0)}
                impact={impact} />
            </Panel>
            <Panel title="Financial Impact" subtitle="are we protecting more than we're costing?">
              <Waterfall impact={impact} />
            </Panel>
          </div>
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
              {rulesRanked.map(r => (
                <RuleHealthCard key={r.rule} r={r} cfg={ruleCfg[r.rule]}
                  isAdmin={isAdmin} busy={cfgBusy} onUpdate={handleRuleUpdate} />
              ))}
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
