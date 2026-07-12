import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { getEventByOrderId, getAiAnalysis, getUserRiskProfile, submitReview, getReviewQueue } from '../services/api';
import NavBar from '../components/NavBar';
import RiskBadge from '../components/RiskBadge';
import LoadingSpinner from '../components/LoadingSpinner';
import { useDictation, useTts } from '../hooks/useSpeech';

// 朗读用的口语化简报 —— 不念原始订单号/哈希（听起来像乱码），把规则名拆成人话，
// 用本产品一贯的"结论优先"口吻。审核员可以边听风险边扫证据，解放眼睛。
// A spoken briefing in the product's house voice. It never reads raw IDs or hashes
// (they sound like noise), un-camelCases rule names, and leads with the conclusion —
// so a reviewer can hear the risk while their eyes scan the evidence.
function spokenBriefing(event, ai) {
  const pct = Math.round((event.riskScore ?? 0) * 100);
  const humanize = (s) => (s ?? '').replace(/Rule\b/g, '').replace(/([a-z])([A-Z])/g, '$1 $2').trim();
  const rules = (event.triggeredRules ?? []).map(humanize).filter(Boolean).join(', ');
  const avsMismatch = event.shippingAddress && event.billingAddress
    && event.shippingAddress !== event.billingAddress;
  return [
    `This order scored ${event.riskLevel ?? 'unknown'} risk at ${pct} percent.`,
    rules ? `Rules triggered: ${rules}.` : 'No rules triggered.',
    humanize(event.explanation),
    avsMismatch ? 'Billing and shipping addresses do not match — an A V S mismatch.' : '',
    ai?.recommendation
      ? `A I recommends ${ai.recommendation.toLowerCase()}${ai.confidence != null
          ? `, at ${Math.round(ai.confidence * 100)} percent confidence` : ''}.`
      : '',
  ].filter(Boolean).join(' ');
}

export default function OrderDetailPage() {
  const { orderId } = useParams();
  const navigate    = useNavigate();
  const [event,     setEvent]     = useState(null);
  const [loading,   setLoading]   = useState(true);
  const [error,     setError]     = useState('');
  const [ai,        setAi]        = useState(null);   // AI analysis (loaded separately)
  const [aiLoading, setAiLoading] = useState(false);
  const [profile,        setProfile]        = useState(null); // user history + linked accounts
  const [profileLoading, setProfileLoading] = useState(true);
  const [reviewNotes,  setReviewNotes]  = useState('');
  const [reviewing,    setReviewing]    = useState(false);
  const [reviewError,  setReviewError]  = useState('');

  // 听写：最终识别结果追加到笔记（保留已打的字）；朗读：读风险简报
  // Dictation appends finalized phrases to the notes (preserving anything typed);
  // TTS reads the risk briefing aloud
  const dictation = useDictation({
    onResult: (text) => setReviewNotes(prev => (prev.trim() ? prev.trim() + ' ' : '') + text),
  });
  const tts = useTts();
  // 待审队列快照：让审核员能在订单之间前后翻页，而不是每单都退回队列面板
  // Review-queue snapshot: lets reviewers page between orders instead of
  // bouncing back to the queue panel after every decision
  const [queueIds, setQueueIds] = useState([]);

  useEffect(() => {
    // 队列按期望损失排序（与Review Queue页一致），失败时安静地隐藏翻页按钮
    // Same expected-loss order as the Review Queue page; on failure the
    // prev/next buttons simply don't render
    getReviewQueue()
      .then(queue => setQueueIds(queue.map(e => e.orderId)))
      .catch(() => setQueueIds([]));
  }, [orderId]);

  const queuePos = queueIds.indexOf(orderId);
  const prevOrderId = queuePos > 0 ? queueIds[queuePos - 1] : null;
  const nextOrderId = queuePos >= 0 && queuePos < queueIds.length - 1
    ? queueIds[queuePos + 1] : null;

  useEffect(() => {
    getEventByOrderId(orderId)
      .then(data => {
        setEvent(data);
        // 如果存储的事件已含AI分析结果，直接使用；否则展示"Run AI Analysis"按钮
        // If the stored event already has AI enrichment, surface it inline
        if (data.aiEnhanced) {
          setAi({
            aiRiskLevel:    data.aiRiskLevel,
            confidence:     data.aiConfidence,
            reasoning:      data.aiReasoning,
            recommendation: data.aiRecommendation,
            keyFactors:     data.aiKeyFactors ?? [],
            aiEnhanced:     true,
          });
        }
        setLoading(false);
        return getUserRiskProfile(data.userId)
          .then(setProfile)
          .catch(() => {}) // profile is a supplementary panel — its failure shouldn't block the order view
          .finally(() => setProfileLoading(false));
      })
      .catch(() => {
        setError('Order not found or an error occurred.');
        setLoading(false);
        setProfileLoading(false);
      });
  }, [orderId]);

  function handleReview(decision) {
    setReviewing(true);
    setReviewError('');
    submitReview(orderId, decision, reviewNotes || null)
      .then(setEvent) // 后端返回更新后的事件 / backend returns the updated event
      .catch(err => setReviewError(err.response?.data?.message || 'Failed to submit review.'))
      .finally(() => setReviewing(false));
  }

  function runAiAnalysis() {
    setAiLoading(true);
    getAiAnalysis(orderId)
      .then(setAi)
      .catch(() => setAi({ reasoning: 'AI analysis failed. Check backend logs.', aiEnhanced: false }))
      .finally(() => setAiLoading(false));
  }

  return (
    <div className="min-h-screen">
      <NavBar />
      <div className="max-w-3xl mx-auto px-4 py-8 space-y-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <button
            onClick={() => navigate('/dashboard')}
            className="flex items-center gap-2 text-sm text-slate-400 hover:text-white transition-colors"
          >
            ← Back to Dashboard
          </button>

          {/* 队列翻页器：当前订单在待审队列中时显示，按期望损失顺序前后移动
              Queue pager — shown while this order is in the review queue; moves
              through the same expected-loss order the queue page uses */}
          {queuePos >= 0 && (
            <div className="flex items-center gap-2">
              <button
                onClick={() => prevOrderId && navigate(`/orders/${prevOrderId}`)}
                disabled={!prevOrderId}
                className="text-sm px-3 py-1.5 bg-dark-card/80 backdrop-blur-sm shadow-lg shadow-black/20 border border-white/10 hover:border-indigo-500/50 disabled:opacity-40 disabled:hover:border-white/10 text-slate-300 rounded-lg transition-colors"
              >
                ← Prev
              </button>
              <span className="text-xs text-slate-500 whitespace-nowrap">
                {queuePos + 1} of {queueIds.length} in queue
              </span>
              <button
                onClick={() => nextOrderId && navigate(`/orders/${nextOrderId}`)}
                disabled={!nextOrderId}
                className="text-sm px-3 py-1.5 bg-dark-card/80 backdrop-blur-sm shadow-lg shadow-black/20 border border-white/10 hover:border-indigo-500/50 disabled:opacity-40 disabled:hover:border-white/10 text-slate-300 rounded-lg transition-colors"
              >
                Next →
              </button>
            </div>
          )}

          {/* 争议证据包：订单+判定+审核+审计链哈希一键成文档，打chargeback官司用
              Dispute evidence: order + verdict + review + chain hashes as one document */}
          {event && (
            <button
              onClick={() => navigate(`/orders/${orderId}/evidence`)}
              className="text-sm px-4 py-2 bg-slate-700 hover:bg-slate-600 text-white rounded-lg transition-colors"
            >
              📄 Export Dispute Evidence
            </button>
          )}
        </div>

        {loading ? <LoadingSpinner /> : error ? (
          <div className="p-4 bg-rose-500/10 border border-rose-500/30 rounded-2xl text-rose-300">{error}</div>
        ) : event && (
          <>
            {/* Order Info Card */}
            <div className="bg-dark-card/80 backdrop-blur-sm shadow-lg shadow-black/20 border border-white/10 rounded-2xl p-6">
              <h1 className="text-lg font-semibold text-white mb-4">Order Details</h1>
              <dl className="grid grid-cols-2 gap-4">
                {[
                  ['Order ID',   event.orderId],
                  ['User ID',    event.userId],
                  ['Amount',     `$${event.amount?.toFixed(2)}`],
                  ['IP Address', event.ipAddress],
                  ['Device', event.deviceId ?? '—'],
                  ['Ship To', event.shippingAddress ?? '—'],
                  ['Bill To', event.billingAddress ?? '—'],
                  ['Detected',   event.detectedAt ? new Date(event.detectedAt).toLocaleString() : '—'],
                ].map(([label, val]) => (
                  <div key={label}>
                    <dt className="text-xs text-slate-500 uppercase tracking-wide">{label}</dt>
                    <dd className="text-sm text-slate-200 font-mono mt-0.5">{val}</dd>
                  </div>
                ))}
              </dl>
              {/* AVS不符提示：账单地址≠收货地址是地址模式检测的辅助信号
                  AVS mismatch cue — billing ≠ shipping, a secondary address-pattern signal */}
              {event.shippingAddress && event.billingAddress &&
                event.shippingAddress !== event.billingAddress && (
                <p className="mt-4 text-xs text-amber-400 bg-amber-900/20 border border-amber-500/30 rounded-lg px-3 py-2">
                  ⚠ Billing address differs from shipping address (AVS mismatch)
                </p>
              )}
            </div>

            {/* Risk Assessment Card */}
            <div className="bg-dark-card/80 backdrop-blur-sm shadow-lg shadow-black/20 border border-white/10 rounded-2xl p-6 space-y-5">
              <div className="flex items-center justify-between gap-3">
                <h2 className="text-lg font-semibold text-white">Risk Assessment</h2>
                {/* 朗读风险简报 —— 眼睛看证据、耳朵听结论 / hear the verdict, eyes on the evidence */}
                {tts.supported && (
                  <button
                    type="button"
                    onClick={() => tts.speaking ? tts.stop() : tts.speak(spokenBriefing(event, ai))}
                    className={`inline-flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg transition-colors ${
                      tts.speaking
                        ? 'bg-indigo-500/20 text-indigo-200 ring-1 ring-indigo-500/40'
                        : 'bg-white/5 text-slate-300 hover:text-white'
                    }`}
                  >
                    <span className={tts.speaking ? 'animate-pulse' : ''}>{tts.speaking ? '⏹' : '🔊'}</span>
                    {tts.speaking ? 'Stop' : 'Read aloud'}
                  </button>
                )}
              </div>

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
                <div className="p-3 bg-dark-bg rounded-lg border border-white/10">
                  <p className="text-xs text-slate-400 uppercase tracking-wide mb-1">Explanation</p>
                  <p className="text-sm text-slate-300">{event.explanation}</p>
                </div>
              )}
            </div>

            {/* Review Decision Card — the human decision that closes the loop */}
            <div className="bg-dark-card/80 backdrop-blur-sm shadow-lg shadow-black/20 border border-white/10 rounded-2xl p-6 space-y-4">
              <h2 className="text-lg font-semibold text-white">Review Decision</h2>

              {event.reviewStatus && event.reviewStatus !== 'PENDING_REVIEW' ? (
                <div className="space-y-2">
                  <span className={`inline-block text-xs font-semibold px-3 py-1 rounded-full ${
                    event.reviewStatus === 'CONFIRMED_FRAUD'
                      ? 'bg-red-900/40 text-red-300 border border-red-500/30'
                      : event.reviewStatus === 'FALSE_POSITIVE'
                        ? 'bg-slate-700/40 text-slate-300 border border-slate-500/30'
                        : 'bg-green-900/40 text-green-300 border border-green-500/30'
                  }`}>
                    {event.reviewStatus.replace('_', ' ')}
                  </span>
                  <p className="text-sm text-slate-400">
                    Reviewed by <span className="text-slate-200">{event.reviewedBy}</span>
                    {event.reviewedAt && <> on {new Date(event.reviewedAt).toLocaleString()}</>}
                  </p>
                  {event.reviewNotes && (
                    <div className="p-3 bg-dark-bg rounded-lg border border-white/10">
                      <p className="text-xs text-slate-400 uppercase tracking-wide mb-1">Notes</p>
                      <p className="text-sm text-slate-300">{event.reviewNotes}</p>
                    </div>
                  )}
                  {/* 决定提交后直接进入下一单 —— 审核是流水线作业，不该每单都退回面板
                      Straight to the next order after deciding — reviewing is assembly-line
                      work; nobody should bounce back to the queue panel between orders */}
                  {queuePos >= 0 && (
                    <div className="pt-2">
                      {nextOrderId ? (
                        <button
                          onClick={() => navigate(`/orders/${nextOrderId}`)}
                          className="text-sm px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg transition-colors"
                        >
                          Review Next Order →
                        </button>
                      ) : (
                        <button
                          onClick={() => navigate('/review')}
                          className="text-sm px-4 py-2 bg-slate-700 hover:bg-slate-600 text-white rounded-lg transition-colors"
                        >
                          Queue done — back to Review Queue
                        </button>
                      )}
                    </div>
                  )}
                </div>
              ) : (
                <>
                  <p className="text-sm text-slate-500">
                    This order is awaiting a decision. Your username and timestamp will be recorded.
                  </p>
                  <div className="relative">
                    <textarea
                      value={reviewNotes + (dictation.interim ? (reviewNotes.trim() ? ' ' : '') + dictation.interim : '')}
                      onChange={e => setReviewNotes(e.target.value)}
                      placeholder="Optional notes (e.g. verified with customer, chargeback reported…)"
                      rows={2}
                      className={`w-full text-sm bg-dark-bg border rounded-lg p-3 pr-28 text-slate-200 placeholder-slate-600 focus:outline-none transition-colors ${
                        dictation.listening ? 'border-rose-500/50 ring-1 ring-rose-500/30' : 'border-white/10 focus:border-indigo-500/50'
                      }`}
                    />
                    {/* 押着说话，识别结果自动追加 —— 审核员的手不用离开证据
                        Push-to-talk dictation; results append so hands stay on the evidence */}
                    {dictation.supported && (
                      <button
                        type="button"
                        onClick={dictation.toggle}
                        className={`absolute bottom-2 right-2 inline-flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-md transition-colors ${
                          dictation.listening
                            ? 'bg-rose-500/20 text-rose-300 ring-1 ring-rose-500/40'
                            : 'bg-white/5 text-slate-400 hover:text-white'
                        }`}
                      >
                        <span className={dictation.listening ? 'animate-pulse' : ''}>🎙</span>
                        {dictation.listening ? 'Listening…' : 'Dictate'}
                      </button>
                    )}
                  </div>
                  {reviewError && <p className="text-sm text-rose-300">{reviewError}</p>}
                  <div className="flex flex-wrap gap-3">
                    <button
                      onClick={() => handleReview('CONFIRMED_FRAUD')}
                      disabled={reviewing}
                      className="text-sm px-4 py-2 bg-red-600 hover:bg-red-700 disabled:opacity-50 text-white rounded-lg transition-colors"
                    >
                      Confirm Fraud
                    </button>
                    <button
                      onClick={() => handleReview('FALSE_POSITIVE')}
                      disabled={reviewing}
                      className="text-sm px-4 py-2 bg-slate-600 hover:bg-slate-700 disabled:opacity-50 text-white rounded-lg transition-colors"
                    >
                      False Positive
                    </button>
                    <button
                      onClick={() => handleReview('APPROVED')}
                      disabled={reviewing}
                      className="text-sm px-4 py-2 bg-green-700 hover:bg-green-800 disabled:opacity-50 text-white rounded-lg transition-colors"
                    >
                      Approve Order
                    </button>
                  </div>
                </>
              )}
            </div>

            {/* AI Analysis Card */}
            <div className="bg-dark-card/80 backdrop-blur-sm shadow-lg shadow-black/20 border border-white/10 rounded-2xl p-6 space-y-5">
              <div className="flex items-center justify-between">
                <h2 className="text-lg font-semibold text-white">AI Analysis</h2>
                {/* 按需触发AI分析（对MEDIUM订单在消费时已自动完成，此按钮用于手动触发其他订单）
                    On-demand trigger — MEDIUM orders are auto-analyzed at ingest time */}
                {!ai && (
                  <button
                    onClick={runAiAnalysis}
                    disabled={aiLoading}
                    className="text-xs px-3 py-1.5 bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 text-white rounded-lg transition-colors"
                  >
                    {aiLoading ? 'Analyzing…' : 'Run AI Analysis'}
                  </button>
                )}
              </div>

              {aiLoading && <LoadingSpinner />}

              {ai && (
                <div className="space-y-4">
                  {/* AI risk level + confidence */}
                  <div className="flex items-center gap-4">
                    <div>
                      <p className="text-xs text-slate-400 uppercase tracking-wide mb-1">AI Risk Level</p>
                      <RiskBadge riskLevel={ai.aiRiskLevel} />
                    </div>
                    {ai.confidence != null && (
                      <div>
                        <p className="text-xs text-slate-400 uppercase tracking-wide mb-1">Confidence</p>
                        <p className="text-sm font-semibold text-slate-200">
                          {(ai.confidence * 100).toFixed(0)}%
                        </p>
                      </div>
                    )}
                    {ai.recommendation && (
                      <div>
                        <p className="text-xs text-slate-400 uppercase tracking-wide mb-1">Recommendation</p>
                        <span className="text-xs bg-amber-900/40 text-amber-300 border border-amber-500/30 px-3 py-1 rounded-full">
                          {ai.recommendation}
                        </span>
                      </div>
                    )}
                  </div>

                  {/* Reasoning */}
                  {ai.reasoning && (
                    <div className="p-3 bg-dark-bg rounded-lg border border-white/10">
                      <p className="text-xs text-slate-400 uppercase tracking-wide mb-1">Reasoning</p>
                      <p className="text-sm text-slate-300">{ai.reasoning}</p>
                    </div>
                  )}

                  {/* Key factors */}
                  {ai.keyFactors?.length > 0 && (
                    <div>
                      <p className="text-xs text-slate-400 uppercase tracking-wide mb-2">Key Factors</p>
                      <div className="flex flex-wrap gap-2">
                        {ai.keyFactors.map(f => (
                          <span key={f} className="text-xs bg-purple-900/40 text-purple-300 border border-purple-500/30 px-3 py-1 rounded-full">
                            {f}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* AI enhanced badge */}
                  <div className="flex items-center gap-2">
                    <span className={`text-xs px-2 py-0.5 rounded-full ${
                      ai.aiEnhanced
                        ? 'bg-green-900/40 text-green-300 border border-green-500/30'
                        : 'bg-slate-700/40 text-slate-400 border border-slate-600/30'
                    }`}>
                      {ai.aiEnhanced ? '✓ AI Enhanced' : '⚠ AI Unavailable'}
                    </span>
                  </div>
                </div>
              )}

              {!ai && !aiLoading && (
                <p className="text-sm text-slate-500">
                  Click "Run AI Analysis" to get an LLM-powered second opinion on this order.
                </p>
              )}
            </div>

            {/* User Risk Profile Card — this user's order history + shared-IP linked accounts */}
            <div className="bg-dark-card/80 backdrop-blur-sm shadow-lg shadow-black/20 border border-white/10 rounded-2xl p-6 space-y-5">
              <h2 className="text-lg font-semibold text-white">User Risk Profile</h2>

              {profileLoading ? <LoadingSpinner /> : !profile ? (
                <p className="text-sm text-slate-500">No profile data available for this user.</p>
              ) : (
                <>
                  <div className="grid grid-cols-2 sm:grid-cols-5 gap-4">
                    {[
                      ['Total Orders', profile.totalOrders],
                      ['High Risk',    profile.highRiskCount],
                      ['Medium Risk',  profile.mediumRiskCount],
                      ['Total Amount', `$${profile.totalAmount?.toFixed(2)}`],
                      // 图传播分数：离已确认欺诈有多近（多跳）/ how close to confirmed fraud (multi-hop)
                      ['Network Risk', profile.graphRiskScore > 0 ? profile.graphRiskScore.toFixed(2) : '—'],
                    ].map(([label, val]) => (
                      <div key={label}>
                        <dt className="text-xs text-slate-500 uppercase tracking-wide">{label}</dt>
                        <dd className="text-sm text-slate-200 font-mono mt-0.5">{val}</dd>
                      </div>
                    ))}
                  </div>

                  {profile.linkedUserIds?.length > 0 && (
                    <div className="p-3 bg-amber-900/20 border border-amber-500/30 rounded-lg">
                      <p className="text-xs text-amber-400 uppercase tracking-wide mb-2">
                        ⚠ Linked accounts (shared IP)
                      </p>
                      <div className="flex flex-wrap gap-2">
                        {profile.linkedUserIds.map(id => (
                          <span
                            key={id}
                            className="text-xs bg-amber-900/40 text-amber-300 border border-amber-500/30 px-3 py-1 rounded-full"
                          >
                            {id}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}

                  <div>
                    <p className="text-xs text-slate-400 uppercase tracking-wide mb-2">Order History</p>
                    {profile.recentEvents?.length === 0 ? (
                      <p className="text-sm text-slate-500">No other orders from this user.</p>
                    ) : (
                      <div className="overflow-x-auto">
                        <table className="w-full text-sm">
                          <thead>
                            <tr className="border-b border-white/5 text-slate-500 text-xs uppercase tracking-wider">
                              {['Time', 'Order ID', 'Amount', 'Risk'].map(h => (
                                <th key={h} className="px-3 py-2 text-left font-medium">{h}</th>
                              ))}
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-white/5">
                            {profile.recentEvents.map(e => (
                              <tr
                                key={e.id}
                                onClick={() => e.orderId !== orderId && navigate(`/orders/${e.orderId}`)}
                                className={e.orderId === orderId ? 'bg-indigo-900/20' : 'hover:bg-white/[0.03] cursor-pointer transition-colors'}
                              >
                                <td className="px-3 py-2 text-slate-400 whitespace-nowrap">
                                  {e.detectedAt ? new Date(e.detectedAt).toLocaleString() : '—'}
                                </td>
                                <td className="px-3 py-2 font-mono text-xs text-indigo-300">{e.orderId}</td>
                                <td className="px-3 py-2 text-slate-300">${e.amount?.toFixed(2)}</td>
                                <td className="px-3 py-2"><RiskBadge riskLevel={e.riskLevel} /></td>
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
          </>
        )}
      </div>
    </div>
  );
}
