import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { getEventByOrderId } from '../services/api';
import NavBar from '../components/NavBar';
import RiskBadge from '../components/RiskBadge';
import LoadingSpinner from '../components/LoadingSpinner';

export default function OrderDetailPage() {
  const { orderId } = useParams();
  const navigate    = useNavigate();
  const [event,   setEvent]   = useState(null);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState('');

  useEffect(() => {
    getEventByOrderId(orderId)
      .then(setEvent)
      .catch(() => setError('Order not found or an error occurred.'))
      .finally(() => setLoading(false));
  }, [orderId]);

  return (
    <div className="min-h-screen bg-dark-bg">
      <NavBar />
      <div className="max-w-3xl mx-auto px-4 py-8 space-y-6">
        <button
          onClick={() => navigate('/dashboard')}
          className="flex items-center gap-2 text-sm text-slate-400 hover:text-white transition-colors"
        >
          ← Back to Dashboard
        </button>

        {loading ? <LoadingSpinner /> : error ? (
          <div className="p-4 bg-red-500/10 border border-red-500/30 rounded-xl text-red-400">{error}</div>
        ) : event && (
          <>
            {/* Order Info Card */}
            <div className="bg-dark-card border border-dark-border rounded-xl p-6">
              <h1 className="text-lg font-semibold text-white mb-4">Order Details</h1>
              <dl className="grid grid-cols-2 gap-4">
                {[
                  ['Order ID',   event.orderId],
                  ['User ID',    event.userId],
                  ['Amount',     `$${event.amount?.toFixed(2)}`],
                  ['IP Address', event.ipAddress],
                  ['Detected',   event.detectedAt ? new Date(event.detectedAt).toLocaleString() : '—'],
                ].map(([label, val]) => (
                  <div key={label}>
                    <dt className="text-xs text-slate-500 uppercase tracking-wide">{label}</dt>
                    <dd className="text-sm text-slate-200 font-mono mt-0.5">{val}</dd>
                  </div>
                ))}
              </dl>
            </div>

            {/* Risk Assessment Card */}
            <div className="bg-dark-card border border-dark-border rounded-xl p-6 space-y-5">
              <h2 className="text-lg font-semibold text-white">Risk Assessment</h2>

              <div className="flex items-center gap-3">
                <span className="text-sm text-slate-400">Risk Level</span>
                <RiskBadge riskLevel={event.riskLevel} />
              </div>

              {/* Risk score progress bar */}
              <div>
                <div className="flex justify-between text-xs text-slate-400 mb-1.5">
                  <span>Risk Score</span>
                  <span>{((event.riskScore ?? 0) * 100).toFixed(0)}%</span>
                </div>
                <div className="w-full bg-dark-bg rounded-full h-2.5">
                  <div
                    className="h-2.5 rounded-full transition-all"
                    style={{
                      width: `${(event.riskScore ?? 0) * 100}%`,
                      background: event.riskScore >= 0.8 ? '#ef4444' :
                                  event.riskScore >= 0.5 ? '#f97316' : '#22c55e',
                    }}
                  />
                </div>
              </div>

              {/* Triggered rules */}
              {event.triggeredRules?.length > 0 && (
                <div>
                  <p className="text-xs text-slate-400 uppercase tracking-wide mb-2">Triggered Rules</p>
                  <div className="flex flex-wrap gap-2">
                    {event.triggeredRules.map(r => (
                      <span key={r} className="text-xs bg-indigo-900/40 text-indigo-300 border border-indigo-500/30 px-3 py-1 rounded-full">
                        {r}
                      </span>
                    ))}
                  </div>
                </div>
              )}

              {/* Explanation */}
              {event.explanation && (
                <div className="p-3 bg-dark-bg rounded-lg border border-dark-border">
                  <p className="text-xs text-slate-400 uppercase tracking-wide mb-1">Explanation</p>
                  <p className="text-sm text-slate-300">{event.explanation}</p>
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
