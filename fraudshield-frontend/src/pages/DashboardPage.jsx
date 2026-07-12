import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  PieChart, Pie, Cell, Tooltip, Legend, ResponsiveContainer,
  ComposedChart, Line, Area, XAxis, YAxis, CartesianGrid,
  BarChart, Bar, LabelList,
} from 'recharts';
import {
  getDashboardStats, getRecentEvents, triggerTestOrders,
  getFinancialImpact, getReviewQueue,
} from '../services/api';
import NavBar from '../components/NavBar';
import RiskBadge from '../components/RiskBadge';
import LoadingSpinner from '../components/LoadingSpinner';

// 风险等级状态色（全应用统一语义）/ risk-level status colors (app-wide semantics)
const RISK_COLORS = { HIGH: '#f43f5e', MEDIUM: '#fb923c', LOW: '#eab308', NORMAL: '#34d399' };

const CHART_TOOLTIP = {
  background: 'rgba(15,23,42,0.95)',
  border: '1px solid #334155',
  borderRadius: 12,
  boxShadow: '0 10px 30px rgba(0,0,0,0.4)',
  fontSize: 12,
};

// ── 极简内联SVG图标 —— 比emoji更统一、更"产品级" / tiny inline SVG icons, crisper than emoji ──
function Icon({ name }) {
  const p = {
    orders: 'M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.3 2.3M17 13l2 2M9 20a1 1 0 100-2 1 1 0 000 2zm8 0a1 1 0 100-2 1 1 0 000 2z',
    high:   'M12 9v4m0 4h.01M10.3 3.9 1.8 18a2 2 0 001.7 3h16.9a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z',
    medium: 'M12 8v4m0 4h.01M12 3a9 9 0 100 18 9 9 0 000-18z',
    rate:   'M3 3v18h18M7 15l3-4 3 3 5-7',
  }[name];
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
         strokeLinecap="round" strokeLinejoin="round" className="w-5 h-5">
      <path d={p} />
    </svg>
  );
}

// 迷你SVG趋势线（用在KPI卡里）/ tiny inline sparkline for KPI cards
function Sparkline({ points, color, id }) {
  const clean = (points ?? []).map(v => (Number.isFinite(v) ? v : 0));
  if (clean.length < 2) return <div className="h-8 mt-3" />;
  const w = 140, h = 32, max = Math.max(...clean, 1);
  const step = w / (clean.length - 1);
  const xy = clean.map((v, i) => [i * step, h - (v / max) * (h - 5) - 2]);
  const line = xy.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)} ${y.toFixed(1)}`).join(' ');
  const area = `${line} L ${w} ${h} L 0 ${h} Z`;
  return (
    <svg viewBox={`0 0 ${w} ${h}`} width="100%" height={h} preserveAspectRatio="none" className="mt-3">
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.4" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={area} fill={`url(#${id})`} />
      <path d={line} fill="none" stroke={color} strokeWidth="2"
            vectorEffect="non-scaling-stroke" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function KpiCard({ label, value, sub, accent, icon, delta, spark, sparkId }) {
  // 风险类指标"涨"是坏事 → 红↑绿↓ / for risk metrics, up is bad: red up, green down
  const deltaBadge = delta == null || delta === 0 ? null : (
    <span className={`ml-2 align-middle text-xs font-semibold px-1.5 py-0.5 rounded-md ${
      delta > 0 ? 'text-rose-300 bg-rose-500/10' : 'text-emerald-300 bg-emerald-500/10'}`}>
      {delta > 0 ? '▲' : '▼'} {Math.abs(delta)}
    </span>
  );
  return (
    <div
      className="rise-in group rounded-2xl p-5 border border-white/5 bg-gradient-to-br from-dark-card to-[#141d30]
                 shadow-lg shadow-black/20 transition-all duration-200 hover:-translate-y-0.5 hover:shadow-black/40"
      style={{ '--accent': accent }}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-xs font-medium uppercase tracking-wider text-slate-400">{label}</p>
          <p className="mt-1.5 text-3xl font-bold text-white tabular-nums">
            {value ?? '—'}{deltaBadge}
          </p>
          {sub && <p className="text-xs text-slate-500 mt-0.5">{sub}</p>}
        </div>
        <span
          className="shrink-0 grid place-items-center w-10 h-10 rounded-xl ring-1"
          style={{ color: accent, background: `${accent}1a`, borderColor: `${accent}33` }}
        >
          <Icon name={icon} />
        </span>
      </div>
      {spark && <Sparkline points={spark} color={accent} id={sparkId} />}
    </div>
  );
}

// 指挥中心大数字卡 —— 左侧严重度色条 + 卡内一句自然语言结论（数字不解释就是噪音）
// Command-center stat: left severity rail + a one-line "so what" — a number
// without a sentence is just noise (PostHog's habit).
function InsightCard({ label, value, soWhat, accent, mono }) {
  return (
    <div className="rise-in relative overflow-hidden rounded-2xl border border-white/5
                    bg-gradient-to-br from-dark-card to-[#141d30] shadow-lg shadow-black/20
                    pl-5 pr-4 py-4 transition-all duration-200 hover:-translate-y-0.5">
      <span className="absolute left-0 top-0 h-full w-1" style={{ background: accent }} />
      <p className="text-[11px] font-medium uppercase tracking-wider text-slate-400">{label}</p>
      <p className={`mt-1 text-3xl font-bold text-white leading-none ${mono ? 'font-mono' : 'tabular-nums'}`}>
        {value}
      </p>
      <p className="mt-2 text-xs leading-snug text-slate-400">{soWhat}</p>
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

export default function DashboardPage() {
  const navigate = useNavigate();
  const [stats,    setStats]    = useState(null);
  const [events,   setEvents]   = useState([]);
  const [finance,  setFinance]  = useState(null);
  const [queue,    setQueue]    = useState([]);
  const [loading,  setLoading]  = useState(true);
  const [error,    setError]    = useState('');
  const [lastSync, setLastSync] = useState('');
  const [seeding,  setSeeding]  = useState(false);

  const fetchData = useCallback(async () => {
    try {
      // 财务/队列是"决策质量"层，缺了不该整屏报错 —— 各自兜底
      // Finance + queue power the decision-quality layer; degrade them
      // individually so one slow endpoint never blanks the whole board.
      const [s, e, f, q] = await Promise.all([
        getDashboardStats(),
        getRecentEvents(10),
        getFinancialImpact().catch(() => null),
        getReviewQueue().catch(() => []),
      ]);
      setStats(s);
      setEvents(e);
      setFinance(f);
      setQueue(Array.isArray(q) ? q : []);
      setError('');
      setLastSync(new Date().toLocaleTimeString());
    } catch {
      setError('Failed to load data.');
    } finally {
      setLoading(false);
    }
  }, []);

  // 挂载时立即加载，之后每10秒自动刷新 / load on mount, then auto-refresh every 10s
  useEffect(() => {
    fetchData();
    const id = setInterval(fetchData, 10000);
    return () => clearInterval(id); // 必须清理防泄漏 / cleanup to prevent leak
  }, [fetchData]);

  const handleSeedOrders = async () => {
    setSeeding(true);
    try { await triggerTestOrders(); } catch { /* ignore */ }
    setTimeout(() => { fetchData(); setSeeding(false); }, 3000);
  };

  // KPI derived values
  const totalProcessed = stats ? (stats.totalOrders ?? 0) + (stats.normalCount ?? 0) : 0;
  const riskAccent = !stats ? '#6366f1'
    : stats.riskRate > 20 ? '#f43f5e'
    : stats.riskRate > 5  ? '#eab308' : '#34d399';

  // 近8小时风险单序列 —— 喂给KPI迷你趋势 / last-8h risk series feeding the sparklines
  const riskSpark = stats?.hourlyTrend?.slice(-8).map(h => h.riskCount ?? 0) ?? [];

  // Pie chart data — 只保留有值的切片 / drop empty slices
  const pieData = stats ? [
    { name: 'HIGH',   value: stats.highRiskCount   ?? 0 },
    { name: 'MEDIUM', value: stats.mediumRiskCount ?? 0 },
    { name: 'LOW',    value: stats.lowRiskCount    ?? 0 },
    { name: 'NORMAL', value: stats.normalCount     ?? 0 },
  ].filter(d => d.value > 0) : [];
  // 向下取整：只要还有风险单，就不会四舍五入成误导性的"100%"
  // Floor, so it never rounds up to a misleading "100%" while any order is flagged
  const normalPct = totalProcessed > 0
    ? Math.floor(((stats?.normalCount ?? 0) / totalProcessed) * 100) : 0;

  // Bar chart — 按命中次数降序，突出误报最多的规则 / sort desc so the loudest rules lead
  const barData = stats?.ruleHitCounts
    ? Object.entries(stats.ruleHitCounts)
        .map(([name, count]) => ({ name: name.replace(/Rule$/, ''), count }))
        .sort((a, b) => b.count - a.count)
    : [];

  // Hourly trend — last 8h. 阴影带 = 7日同钟点均值 ± 2σ / band = same-hour 7-day mean ± 2σ
  const trendData = stats?.hourlyTrend?.slice(-8).map(h => ({
    hour:       h.hour.split(' ')[1],
    orders:     h.orderCount,
    riskOrders: h.riskCount,
    baseline:   h.baselineRisk,
    band: [
      Math.max(0, (h.baselineRisk ?? 0) - 2 * (h.baselineSigma ?? 0)),
      (h.baselineRisk ?? 0) + 2 * (h.baselineSigma ?? 0),
    ],
  })) ?? [];

  // ── Decision-Quality Command Center —— 用美元和精度讲"我们在赢吗" ──
  //    Money + precision answering finance's one question: are we winning?
  const netProtected = finance
    ? (finance.interceptedAmount ?? 0) - (finance.falsePositiveAmount ?? 0) : null;
  const killRatio = finance?.interceptToFalseKillRatio; // intercepted$ per $1 wrongly killed

  // 队列期望损失 = Σ score×amount —— 比"件数"更能回答"先审哪单"
  // Queue expected loss = Σ score×amount; drives triage far better than a count.
  const queueExpLoss = queue.reduce((s, e) => s + (e.riskScore ?? 0) * (e.amount ?? 0), 0);

  // ── Attack Radar —— 对每个钟点做 baseline ± 2σ 比较，自动冒出异常 ──
  //    Same-hour baseline comparison; anomalies surface themselves (GA4 Insights style).
  const radar = (stats?.hourlyTrend ?? [])
    .map(h => {
      const sigma = h.baselineSigma ?? 0;
      const base  = h.baselineRisk ?? 0;
      const z = sigma > 0 ? (h.riskCount - base) / sigma : (h.riskCount > base ? 99 : 0);
      return { hour: h.hour, riskCount: h.riskCount, base, z };
    })
    .filter(h => h.z >= 2 && h.riskCount >= 3)     // 2σ门槛 + 绝对量地板，压掉噪声 / floor kills noise
    .sort((a, b) => b.z - a.z)
    .slice(0, 3);

  // 威胁等级：异常强度优先，其次风险率 / threat = anomaly strength first, then risk rate
  const topZ = radar[0]?.z ?? 0;
  const threat =
    topZ >= 3 || (stats?.riskRate ?? 0) > 20 ? { label: 'ELEVATED', c: '#f43f5e', k: 'red'   }
    : topZ >= 2 || (stats?.riskRate ?? 0) > 5 ? { label: 'GUARDED',  c: '#fb923c', k: 'amber' }
    :                                           { label: 'NOMINAL',  c: '#34d399', k: 'green' };

  const fmtUsd = (n) => n == null ? '—'
    : (n < 0 ? '-$' : '$') + Math.abs(Math.round(n)).toLocaleString();

  return (
    <div className="min-h-screen">
      <NavBar />

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">

        {/* Header bar */}
        <div className="flex items-center justify-between flex-wrap gap-3">
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold text-white tracking-tight">Dashboard</h1>
            <span className="flex items-center gap-1.5 text-xs text-emerald-300 bg-emerald-500/10 ring-1 ring-emerald-500/20 px-2.5 py-1 rounded-full">
              <span className="w-1.5 h-1.5 bg-emerald-400 rounded-full animate-pulse" />
              LIVE
            </span>
          </div>
          <div className="flex items-center gap-3">
            <span className="text-xs text-slate-500">Updated {lastSync || '—'}</span>
            <button
              onClick={handleSeedOrders}
              disabled={seeding}
              className="text-sm font-medium px-4 py-2 rounded-xl text-white bg-gradient-to-r from-indigo-500 to-violet-500
                         shadow-lg shadow-indigo-900/30 hover:from-indigo-400 hover:to-violet-400
                         disabled:opacity-50 transition-all active:scale-95"
            >
              {seeding ? 'Sending…' : '⚡ Send Test Orders'}
            </button>
          </div>
        </div>

        {/* Threat Level strip —— 全屏签名视觉：作战室的"当前态势"一眼可读
            Signature visual: a mission-control status rail read in one glance */}
        {!loading && (
          <div className="rise-in flex items-center gap-3 rounded-xl border px-4 py-2.5"
               style={{ borderColor: `${threat.c}40`, background: `${threat.c}12` }}>
            <span className="relative flex h-2.5 w-2.5 shrink-0">
              {threat.k !== 'green' && (
                <span className="absolute inline-flex h-full w-full rounded-full opacity-60 animate-ping"
                      style={{ background: threat.c }} />
              )}
              <span className="relative inline-flex h-2.5 w-2.5 rounded-full" style={{ background: threat.c }} />
            </span>
            <span className="font-mono text-xs font-semibold tracking-widest" style={{ color: threat.c }}>
              THREAT&nbsp;LEVEL&nbsp;·&nbsp;{threat.label}
            </span>
            <span className="text-xs text-slate-400 truncate">
              {radar.length
                ? `${radar.length} anomal${radar.length > 1 ? 'ies' : 'y'} above 7-day baseline · top spike ${topZ >= 99 ? '' : topZ.toFixed(1) + 'σ'}`
                : `${queue.length} in queue · ${fmtUsd(queueExpLoss)} expected loss at stake`}
            </span>
          </div>
        )}

        {error && (
          <div className="p-4 bg-rose-500/10 border border-rose-500/30 rounded-xl text-rose-300 flex items-center justify-between">
            <span>{error}</span>
            <button onClick={fetchData} className="text-sm underline">Retry</button>
          </div>
        )}

        {loading ? <LoadingSpinner /> : (
          <>
            {/* Section 0 — Decision-Quality Command Center + Attack Radar
                每屏回答一个问题："我们在赢吗 / 该审什么" —— 叙事而非罗列
                Each screen answers one question: are we winning / what to review. */}
            <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
              <div className="xl:col-span-2 space-y-4">
                <div className="flex items-baseline justify-between">
                  <h2 className="text-base font-semibold text-white">Command Center</h2>
                  <span className="text-xs text-slate-500">are we winning?</span>
                </div>
                <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
                  <InsightCard
                    label="Net Protected" value={fmtUsd(netProtected)} accent="#34d399"
                    soWhat={netProtected == null ? 'Awaiting reviewed labels.'
                      : netProtected >= 0
                        ? `Fraud $ stopped, net of revenue wrongly blocked.`
                        : `We're killing more good revenue than fraud — loosen thresholds.`} />
                  <InsightCard
                    label="Intercept : False-kill" mono
                    value={killRatio == null ? '—' : `${killRatio.toFixed(1)}×`} accent="#818cf8"
                    soWhat={killRatio == null ? 'No wrongful blocks yet — clean run.'
                      : `$${killRatio.toFixed(1)} of fraud caught per $1 of good revenue lost.`} />
                  <InsightCard
                    label="Queue Exposure" value={fmtUsd(queueExpLoss)} accent="#fb923c"
                    soWhat={queue.length
                      ? `Expected loss across ${queue.length} orders awaiting a decision.`
                      : 'Queue is empty — nothing pending.'} />
                  <InsightCard
                    label="Losses Avoided" value={fmtUsd(finance?.interceptedAmount)} accent="#f43f5e"
                    soWhat={finance?.interceptedCount
                      ? `${finance.interceptedCount} confirmed-fraud orders that never charged back.`
                      : 'No confirmed fraud recorded yet.'} />
                </div>
              </div>

              {/* Attack Radar —— 自动异常卡，点它跳到已筛选的队列 / auto-anomaly cards → filtered queue */}
              <Panel title="Attack Radar" subtitle="hours breaching the 7-day same-hour baseline">
                {radar.length === 0 ? (
                  <div className="py-8 text-center">
                    <p className="text-3xl">🦔</p>
                    <p className="mt-2 text-sm text-slate-400">All quiet — nothing above baseline.</p>
                    <p className="text-xs text-slate-600">Either you're good, or the fraudsters are on lunch.</p>
                  </div>
                ) : (
                  <div className="space-y-2.5">
                    {radar.map((h) => {
                      const sev = h.z >= 3 ? '#f43f5e' : '#fb923c';
                      return (
                        <button
                          key={h.hour}
                          onClick={() => navigate('/review')}
                          className="w-full text-left relative overflow-hidden rounded-xl border border-white/5
                                     bg-white/[0.02] hover:bg-white/[0.05] transition-colors pl-4 pr-3 py-3"
                        >
                          <span className="absolute left-0 top-0 h-full w-1" style={{ background: sev }} />
                          <div className="flex items-center justify-between gap-2">
                            <span className="font-mono text-xs" style={{ color: sev }}>
                              {h.z >= 99 ? 'NEW' : `${h.z.toFixed(1)}σ`}
                            </span>
                            <span className="font-mono text-[11px] text-slate-500">{h.hour.split(' ')[1]}</span>
                          </div>
                          <p className="mt-1 text-xs text-slate-300 leading-snug">
                            <span className="font-semibold text-white">{h.riskCount}</span> risk orders vs{' '}
                            <span className="tabular-nums">{h.base.toFixed(1)}</span> expected — investigate →
                          </p>
                        </button>
                      );
                    })}
                  </div>
                )}
              </Panel>
            </div>

            {/* Section A — KPI Cards */}
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
              <KpiCard label="Total Processed" value={totalProcessed.toLocaleString()} sub="orders scored"
                       accent="#818cf8" icon="orders" />
              <KpiCard label="High Risk" value={stats?.highRiskCount} sub="flagged for review"
                       accent="#f43f5e" icon="high" delta={stats?.highRiskWowDelta}
                       spark={riskSpark} sparkId="spark-high" />
              <KpiCard label="Medium Risk" value={stats?.mediumRiskCount} sub="AI second opinion"
                       accent="#fb923c" icon="medium" delta={stats?.mediumRiskWowDelta} />
              <KpiCard label="Risk Rate" value={`${stats?.riskRate ?? 0}%`} sub="of all processed"
                       accent={riskAccent} icon="rate" />
            </div>

            {/* Section B — Donut + Trend */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              <Panel title="Risk Distribution" subtitle="share of every order scored">
                {pieData.length === 0 ? (
                  <p className="text-slate-500 text-sm text-center py-10">No data yet — send test orders</p>
                ) : (
                  <>
                    {/* 甜甜圈本体 + 完美居中的中心标签（不用Recharts图例，避免偏移）
                        Donut with a perfectly centered label — no Recharts legend to
                        skew the geometry; a custom HTML legend sits below */}
                    <div className="relative">
                      <ResponsiveContainer width="100%" height={200}>
                        <PieChart>
                          <Pie data={pieData} dataKey="value" nameKey="name" cx="50%" cy="50%"
                               innerRadius={64} outerRadius={90} paddingAngle={2} cornerRadius={4}
                               stroke="none">
                            {pieData.map(d => <Cell key={d.name} fill={RISK_COLORS[d.name]} />)}
                          </Pie>
                          <Tooltip contentStyle={CHART_TOOLTIP}
                                   formatter={(v, n) => [v.toLocaleString(), n]} />
                        </PieChart>
                      </ResponsiveContainer>
                      <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
                        <span className="text-4xl font-bold text-white tabular-nums leading-none">{normalPct}%</span>
                        <span className="text-xs text-slate-500 uppercase tracking-wider mt-1">normal</span>
                      </div>
                    </div>
                    {/* 自定义图例：色点 + 名称 + 计数 / custom legend: dot + name + count */}
                    <div className="mt-3 flex flex-wrap justify-center gap-x-5 gap-y-1.5">
                      {pieData.map(d => (
                        <span key={d.name} className="inline-flex items-center gap-1.5 text-xs">
                          <span className="w-2.5 h-2.5 rounded-full" style={{ background: RISK_COLORS[d.name] }} />
                          <span className="text-slate-400">{d.name}</span>
                          <span className="text-slate-500 tabular-nums">{d.value.toLocaleString()}</span>
                        </span>
                      ))}
                    </div>
                  </>
                )}
              </Panel>

              <Panel title="Hourly Trend" subtitle="last 8h · shaded band = 7-day same-hour mean ± 2σ">
                <ResponsiveContainer width="100%" height={240}>
                  <ComposedChart data={trendData} margin={{ top: 8, right: 8, left: -12, bottom: 0 }}>
                    <defs>
                      <linearGradient id="riskFill" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stopColor="#f43f5e" stopOpacity="0.35" />
                        <stop offset="100%" stopColor="#f43f5e" stopOpacity="0" />
                      </linearGradient>
                    </defs>
                    <CartesianGrid strokeDasharray="3 3" stroke="#1e293b" vertical={false} />
                    <XAxis dataKey="hour" tick={{ fill: '#64748b', fontSize: 11 }} axisLine={false} tickLine={false} />
                    <YAxis tick={{ fill: '#64748b', fontSize: 11 }} axisLine={false} tickLine={false} width={34} />
                    <Tooltip contentStyle={CHART_TOOLTIP} />
                    <Legend iconType="plainline" formatter={(v) => <span className="text-slate-400 text-xs">{v}</span>} />
                    <Area type="monotone" dataKey="band" stroke="none" fill="#f59e0b" fillOpacity={0.10} name="Normal range" />
                    <Area type="monotone" dataKey="riskOrders" stroke="none" fill="url(#riskFill)" name="" legendType="none" />
                    <Line type="monotone" dataKey="baseline"   stroke="#f59e0b" strokeWidth={1.5} strokeDasharray="5 5" dot={false} name="7d baseline" />
                    <Line type="monotone" dataKey="orders"     stroke="#818cf8" strokeWidth={2} dot={false} name="Total" />
                    <Line type="monotone" dataKey="riskOrders" stroke="#f43f5e" strokeWidth={2.5} dot={false} name="Risk" />
                  </ComposedChart>
                </ResponsiveContainer>
              </Panel>
            </div>

            {/* Section C — Rule Trigger Counts */}
            {barData.length > 0 && (
              <Panel title="Rule Trigger Counts" subtitle="how often each detection rule fires">
                <ResponsiveContainer width="100%" height={200}>
                  <BarChart data={barData} margin={{ top: 20, right: 8, left: -12, bottom: 0 }}>
                    <defs>
                      <linearGradient id="barFill" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stopColor="#818cf8" />
                        <stop offset="100%" stopColor="#6366f1" />
                      </linearGradient>
                    </defs>
                    <CartesianGrid strokeDasharray="3 3" stroke="#1e293b" vertical={false} />
                    <XAxis dataKey="name" tick={{ fill: '#94a3b8', fontSize: 12 }} axisLine={false} tickLine={false} interval={0} />
                    <YAxis tick={{ fill: '#64748b', fontSize: 11 }} axisLine={false} tickLine={false} width={34} allowDecimals={false} />
                    <Tooltip cursor={{ fill: 'rgba(129,140,248,0.08)' }} contentStyle={CHART_TOOLTIP} />
                    <Bar dataKey="count" fill="url(#barFill)" radius={[6, 6, 0, 0]} maxBarSize={64}>
                      <LabelList dataKey="count" position="top" fill="#cbd5e1" fontSize={12} fontWeight={600} />
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </Panel>
            )}

            {/* Section D — Recent Events */}
            <Panel title="Recent Risk Events" subtitle="click a row to open the full order review" className="overflow-hidden">
              {events.length === 0 ? (
                <p className="text-slate-500 text-sm text-center py-8">No risk events yet</p>
              ) : (
                <div className="overflow-x-auto -mx-5 -mb-5">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-slate-500 text-xs uppercase tracking-wider border-b border-white/5">
                        {['Time','Order ID','User','IP','Amount','Risk','Rules'].map(h => (
                          <th key={h} className="px-5 py-3 text-left font-medium">{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-white/5">
                      {events.map(e => (
                        <tr
                          key={e.id}
                          onClick={() => navigate(`/orders/${e.orderId}`)}
                          className="cursor-pointer hover:bg-white/[0.03] transition-colors"
                        >
                          <td className="px-5 py-3 text-slate-400 whitespace-nowrap">
                            {e.detectedAt ? new Date(e.detectedAt).toLocaleTimeString() : '—'}
                          </td>
                          <td className="px-5 py-3 font-mono text-xs text-indigo-300">{e.orderId}</td>
                          <td className="px-5 py-3 text-slate-300">{e.userId}</td>
                          <td className="px-5 py-3 text-slate-400 font-mono text-xs">{e.ipAddress}</td>
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
