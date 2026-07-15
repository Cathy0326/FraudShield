# Detecting Invalid Traffic in Mobile Ad Click Streams: A Baseline Study

*A short experimental report — click-fraud / invalid-traffic detection on the
TalkingData AdTracking schema.*

**Abstract.** Invalid traffic (bots, click farms, and attribution abuse) drains a large
share of digital-advertising spend and corrupts the conversion signals advertisers use
to allocate budget. Framed as a supervised problem — predict, per click, whether it will
lead to a genuine app install (`is_attributed`) — this is a severely imbalanced,
adversarial, time-ordered detection task. On a click stream faithful to the TalkingData
AdTracking schema (0.79% positive), a gradient-boosted model over velocity and
aggregation features reaches **ROC-AUC 0.819 / PR-AUC 0.028**, beating both a linear
baseline (0.808) and a naive per-IP click-count heuristic (0.797), and its top-scoring
10% of traffic retains **34% of all true converters** (a 3.4× lift). The exercise is a
warm-up for a PhD direction at the intersection of **data science, adversarial security,
and privacy-preserving measurement** in advertising.

---

## 1. Motivation and threat model

Digital advertising runs on a chain of decisions — *which traffic to pay for, which
sources to trust, where to move budget* — and every link depends on one question: **is
this click/conversion real?** Industry estimates put invalid-traffic losses in the tens
of billions of dollars annually. The adversary is diverse and adaptive:

- **Click farms / bots** — high-volume automated clicks that never convert, often
  concentrated on a few apps, devices, and channels, arriving in tight bursts.
- **Affiliate / attribution abuse** — cookie stuffing, forced/predictive clicks, and
  last-click hijacking that steal attribution for organic installs. *(This threat model
  draws on the author's six years of hands-on performance-marketing and affiliate
  experience — knowing precisely how attribution is gamed informs which features and
  evaluation scenarios actually matter.)*

The defender's signal is asymmetric: genuine installs are rare (<1% of clicks) and the
attacker controls the click side. This makes the problem both **highly imbalanced** and
**adversarial**, which shapes every methodological choice below.

## 2. Data

We use the TalkingData AdTracking schema: `ip, app, device, os, channel, click_time,
is_attributed`, where `is_attributed = 1` marks a click that led to a download.

Because the real corpus is ~200M rows behind an authentication wall, results here are on
a **synthetic stream that reproduces the mechanics the task depends on** (`synth.py`):
a small minority of IPs act as click farms (≈60× the click volume, near-zero
conversion); genuine conversion is driven by *observable* structure — a heavy-tailed
per-app propensity and a mild diurnal effect — rather than unobservable noise, matching
the real data's high feature-predictiveness. The pipeline is schema-identical to the
Kaggle set: `python experiment.py --data train.csv` runs it unchanged on the real data.
Positive rate in this run: **0.79%**.

## 3. Features

Standard click-fraud features, all computable from a short sliding window (and therefore
deployable in a real-time scorer):

- **Aggregation counts** over `ip`, `ip×app`, `ip×device×os`, `app×channel` — bots
  inflate these.
- **Distinct-value spread** per IP (`nunique` app / channel).
- **Time to next / previous click** from the same IP — the dominant signal, since bot
  clicks arrive in bursts.
- **Hour of day.**

## 4. Method

Three deliberate choices keep this a *fair* baseline rather than a leaky demo:

1. **Temporal split** — train on the earlier 75% of clicks, test on the later 25%. Fraud
   is adversarial and time-ordered; a random split leaks future information.
2. **Imbalance-aware metrics** — ROC-AUC *and* PR-AUC (average precision). At 0.79%
   positives, accuracy is meaningless; PR-AUC is the honest headline.
3. **Decision-facing view** — a gains curve: ranking clicks by predicted quality, what
   share of true converters falls in the top *k%* of traffic? This is the number an
   advertiser acts on.

Models: L2 logistic regression (scaled, class-balanced) and histogram gradient-boosted
trees; plus a naive reference that ranks by raw per-IP click count alone.

## 5. Results

| Model | ROC-AUC | PR-AUC | Converters in top 1% / 5% / 10% |
|---|---|---|---|
| Naive (per-IP count) | 0.797 | 0.0223 | 2.6% / 13.3% / 25.4% |
| Logistic regression | 0.808 | 0.0244 | 3.0% / 15.2% / 27.6% |
| **Gradient boosting** | **0.819** | **0.0276** | **3.2% / 18.7% / 34.3%** |

*Chance PR-AUC = the 0.79% base rate; the model's 0.028 is a ~3.5× lift.*

Three takeaways:

- **Engineered features + a non-linear model beat the folk heuristic.** "High-volume IPs
  are bots" (the naive count) already gets ROC-AUC 0.80 — but adding app/channel and
  velocity features and letting gradient boosting model their interactions lifts both
  ranking quality and, more usefully, the gains curve: the top 10% of traffic retains
  **34%** of installs vs 25% for the heuristic.
- **The imbalance is the whole difficulty.** ROC-AUC looks healthy (0.82) while PR-AUC
  stays low (0.028) — the standard, honest tension in rare-event fraud detection, and
  exactly why PR-AUC and the gains curve are reported instead of accuracy.
- **Feature importance is interpretable** (permutation, Δ PR-AUC): `cnt_ip` ≫ `app` >
  `device` > `cnt_app_channel` > `prev_click_sec` / `next_click_sec`. Volume and
  timing dominate — consistent with the click-farm threat model.

The decision value is clearest in the **cumulative gains curve** (`gains_curve.png`,
the "money chart"): ranked by predicted quality, the top ~40% of traffic already
captures ~98% of installs, and gradient boosting dominates the linear model across the
whole range — i.e. an advertiser paying for only the better-scoring traffic keeps almost
all real conversions while shedding most invalid clicks. See also `pr_curve.png`
(imbalance) and `feature_importance.png`.

## 6. Discussion, limitations, and research directions

**Limitations.** Results are on synthetic (if faithful) data; absolute PR-AUC is not
comparable to the real leaderboard, and the synthetic generator cannot capture every
real correlation. The point is a *validated, reproducible pipeline and an honest
baseline*, not a state-of-the-art score.

This baseline opens three research questions that motivate a PhD:

1. **Adversarial concept drift.** Detectors decay as attackers adapt. How should a
   scorer *co-adapt* — reallocating scarce human labels (active learning) and updating
   feature weights — while an adversary actively shifts the distribution? A temporal
   split is the first honest step toward measuring this.
2. **Privacy-preserving measurement.** Post-cookie (GDPR, ATT, Privacy Sandbox),
   IP-level velocity features are increasingly unavailable. Can invalid traffic be
   detected — and conversions measured — under aggregation / differential privacy /
   on-device constraints? This is a live problem for the ad platforms headquartered in
   Ireland and for the EU regulator that oversees them.
3. **Graph-based collusion.** Click farms and affiliate rings are networks; a
   graph-learning view (shared devices/IPs/attribution paths) should out-detect the
   tabular baseline here — with the label scarcity that makes weak/self-supervision
   necessary.

## 7. Reproducibility

```
pip install -r requirements.txt
python experiment.py                 # synthetic (this report)
python experiment.py --data train.csv   # real Kaggle TalkingData CSV, same columns
```
Outputs: `metrics.json`, `pr_curve.png`, `feature_importance.png`. Deterministic seeds.

**Data reference.** TalkingData AdTracking Fraud Detection Challenge (Kaggle, 2018).
Related public datasets for follow-up: Criteo Attribution Modeling, Avazu CTR,
Elliptic (graph AML).
