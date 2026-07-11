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

  return (
    <div className="min-h-screen bg-dark-bg">
      <NavBar />
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-6">
        <h1 className="text-2xl font-bold text-white">Reports</h1>

        {/* Filters */}
        <div className="bg-dark-card border border-dark-border rounded-xl p-5">
          <h2 className="text-base font-semibold text-white mb-4">Date Range</h2>
          <div className="flex flex-wrap items-end gap-4">
            {/* colorScheme:'dark' —— 否则Chrome把日历图标画成黑色，深色背景上完全不可见，
                看起来就像"选不了日期" / without it Chrome renders the calendar icon black
                on our dark background — invisible, so the picker seems broken */}
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">From</label>
              <input type="date" value={startDate} max={endDate || undefined}
                onChange={e => setStartDate(e.target.value)}
                style={{ colorScheme: 'dark' }}
                className="bg-dark-bg border border-dark-border rounded-lg px-3 py-2 text-slate-200 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
            </div>
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">To</label>
              <input type="date" value={endDate} min={startDate || undefined}
                onChange={e => setEndDate(e.target.value)}
                style={{ colorScheme: 'dark' }}
                className="bg-dark-bg border border-dark-border rounded-lg px-3 py-2 text-slate-200 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
            </div>
            <button onClick={handleGenerate} disabled={loading}
              className="px-5 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-medium rounded-lg transition-colors">
              {loading ? 'Generating…' : 'Generate Report'}
            </button>
            {generated && events.length > 0 && (
              <button onClick={() => exportCsv(events)}
                className="px-5 py-2 bg-green-700/40 hover:bg-green-700/60 text-green-300 border border-green-500/30 text-sm font-medium rounded-lg transition-colors">
                ⬇ Export CSV
              </button>
            )}
          </div>
        </div>

        {error && (
          <div className="p-4 bg-red-500/10 border border-red-500/30 rounded-xl text-red-400">{error}</div>
        )}

        {/* Financial Impact — 财务视角：拦截的损失 vs 误杀的营收 / finance view: losses avoided vs revenue wrongly blocked */}
        {impact && (
          <div className="bg-dark-card border border-dark-border rounded-xl p-5">
            <h2 className="text-base font-semibold text-white">Financial Impact</h2>
            <p className="text-xs text-slate-500 mt-1 mb-4">
              From review decisions — every confirmed-fraud dollar is a chargeback avoided;
              every false-positive dollar is legitimate revenue we wrongly blocked.
            </p>
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
              <div className="border border-green-500/30 rounded-lg p-4">
                <p className="text-xs text-slate-400">Fraud Intercepted</p>
                <p className="text-2xl font-bold text-green-400 mt-1">
                  ${impact.interceptedAmount?.toFixed(2)}
                </p>
                <p className="text-xs text-slate-500">{impact.interceptedCount} orders</p>
              </div>
              <div className="border border-amber-500/30 rounded-lg p-4">
                <p className="text-xs text-slate-400">Revenue Wrongly Blocked</p>
                <p className="text-2xl font-bold text-amber-400 mt-1">
                  ${impact.falsePositiveAmount?.toFixed(2)}
                </p>
                <p className="text-xs text-slate-500">{impact.falsePositiveCount} false positives</p>
              </div>
              <div className="border border-dark-border rounded-lg p-4">
                <p className="text-xs text-slate-400">Reviewed &amp; Released</p>
                <p className="text-2xl font-bold text-slate-200 mt-1">
                  ${impact.approvedAmount?.toFixed(2)}
                </p>
                <p className="text-xs text-slate-500">{impact.approvedCount} orders</p>
              </div>
              <div className="border border-indigo-500/30 rounded-lg p-4">
                <p className="text-xs text-slate-400">Intercept : False-Kill Ratio</p>
                <p className="text-2xl font-bold text-indigo-300 mt-1">
                  {impact.interceptToFalseKillRatio == null
                    ? '∞'
                    : `${impact.interceptToFalseKillRatio.toFixed(1)} : 1`}
                </p>
                <p className="text-xs text-slate-500">the fraud program&apos;s one-line ROI</p>
              </div>
            </div>
          </div>
        )}

        {/* Rule Precision — 审核标注反哺规则质量评估 / review labels feeding back into rule quality */}
        <div className="bg-dark-card border border-dark-border rounded-xl overflow-hidden">
          <div className="px-5 py-4 border-b border-dark-border">
            <h2 className="text-base font-semibold text-white">Rule Precision</h2>
            <p className="text-xs text-slate-500 mt-1">
              Computed from review decisions — which rules are firing correctly, and which
              generate the most false alarms. Rules with the most wrong calls sort first.
            </p>
          </div>
          {rulePrecision.length === 0 ? (
            <p className="text-slate-500 text-sm text-center py-8">
              No rule hits recorded yet.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-dark-border text-slate-400 text-xs uppercase">
                    {['Rule', 'Total Hits', 'Reviewed', 'Confirmed Fraud', 'False Alarms', 'Precision'].map(h => (
                      <th key={h} className="px-4 py-3 text-left">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-dark-border">
                  {rulePrecision.map(r => {
                    const falseAlarms = (r.falsePositive ?? 0) + (r.approved ?? 0);
                    return (
                      <tr key={r.rule} className="hover:bg-dark-bg/40 transition-colors">
                        <td className="px-4 py-3 text-slate-200 font-medium">{r.rule}</td>
                        <td className="px-4 py-3 text-slate-300">{r.totalHits}</td>
                        <td className="px-4 py-3 text-slate-400">{r.reviewedHits}</td>
                        <td className="px-4 py-3 text-red-300">{r.confirmedFraud}</td>
                        <td className="px-4 py-3 text-amber-300">{falseAlarms}</td>
                        <td className="px-4 py-3">
                          {r.precision == null ? (
                            <span className="text-xs text-slate-500">no labels yet</span>
                          ) : (
                            <div className="flex items-center gap-2">
                              <div className="w-24 bg-dark-bg rounded-full h-2">
                                <div
                                  className="h-2 rounded-full"
                                  style={{
                                    width: `${r.precision}%`,
                                    background: r.precision >= 80 ? '#22c55e' :
                                                r.precision >= 50 ? '#f97316' : '#ef4444',
                                  }}
                                />
                              </div>
                              <span className={`text-xs font-semibold ${
                                r.precision >= 80 ? 'text-green-400' :
                                r.precision >= 50 ? 'text-orange-400' : 'text-red-400'
                              }`}>
                                {r.precision.toFixed(0)}%
                              </span>
                            </div>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {loading ? <LoadingSpinner /> : generated && (
          <>
            {/* Summary cards */}
            <div className="grid grid-cols-3 gap-4">
              {[
                { label: 'Total in Range', value: events.length },
                { label: 'High Risk',      value: high,   cls: 'border-red-500/30' },
                { label: 'Medium Risk',    value: medium, cls: 'border-orange-500/30' },
              ].map(c => (
                <div key={c.label} className={`bg-dark-card border ${c.cls ?? 'border-dark-border'} rounded-xl p-4`}>
                  <p className="text-xs text-slate-400">{c.label}</p>
                  <p className="text-2xl font-bold text-white mt-1">{c.value}</p>
                </div>
              ))}
            </div>

            {/* Results table */}
            <div className="bg-dark-card border border-dark-border rounded-xl overflow-hidden">
              <div className="px-5 py-4 border-b border-dark-border">
                <h2 className="text-base font-semibold text-white">
                  Report Results ({events.length} events)
                </h2>
              </div>
              {events.length === 0 ? (
                <p className="text-slate-500 text-center py-10 text-sm">No events found</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-dark-border text-slate-400 text-xs uppercase">
                        {['Time','Order ID','User','IP','Amount','Risk Level','Rules'].map(h => (
                          <th key={h} className="px-4 py-3 text-left">{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-dark-border">
                      {events.map(e => (
                        <tr key={e.id} className="hover:bg-dark-bg/40 transition-colors">
                          <td className="px-4 py-3 text-slate-400 whitespace-nowrap">
                            {e.detectedAt ? new Date(e.detectedAt).toLocaleString() : '—'}
                          </td>
                          <td className="px-4 py-3 font-mono text-xs text-slate-300">{e.orderId}</td>
                          <td className="px-4 py-3 text-slate-300">{e.userId}</td>
                          <td className="px-4 py-3 font-mono text-xs text-slate-400">{e.ipAddress}</td>
                          <td className="px-4 py-3 text-slate-300">${e.amount?.toFixed(2)}</td>
                          <td className="px-4 py-3"><RiskBadge riskLevel={e.riskLevel} /></td>
                          <td className="px-4 py-3 text-slate-400 text-xs">{e.triggeredRules?.join(', ')}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
