"""
Click-fraud / invalid-traffic detection baseline.

Run on synthetic data (default) or the real Kaggle TalkingData CSV:
    python experiment.py                     # synthetic, faithful to the schema
    python experiment.py --data train.csv    # real data, same columns

Design choices that make this a *fair* baseline rather than a leaky demo:
  * Temporal train/test split (train on the earlier clicks, test on the later ones).
    Fraud is adversarial and time-ordered; a random split leaks the future.
  * Imbalance-aware metrics: ROC-AUC *and* PR-AUC (average precision). With ~0.2%
    positives, accuracy is meaningless and PR-AUC is what actually matters.
  * A decision-facing view: at each "block rate" (share of traffic flagged), how much
    invalid traffic do we catch? That is the number an advertiser/platform acts on.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.pipeline import make_pipeline
from sklearn.metrics import roc_auc_score, average_precision_score, precision_recall_curve, roc_curve
from sklearn.inspection import permutation_importance

from synth import generate_synthetic
from features import add_features, FEATURE_COLS

OUT = Path(__file__).parent


def load(data_path: str | None) -> pd.DataFrame:
    if data_path:
        df = pd.read_csv(data_path, parse_dates=["click_time"])
        print(f"Loaded real data: {df.shape}")
    else:
        df = generate_synthetic()
        print(f"Generated synthetic data: {df.shape}")
    return df


def temporal_split(df: pd.DataFrame, test_frac: float = 0.25):
    df = df.sort_values("click_time").reset_index(drop=True)
    cut = int(len(df) * (1 - test_frac))
    return df.iloc[:cut], df.iloc[cut:]


def evaluate(name, y_true, scores, results):
    roc = roc_auc_score(y_true, scores)
    ap = average_precision_score(y_true, scores)
    results[name] = {"roc_auc": round(roc, 4), "pr_auc": round(ap, 4)}
    # decision view (gains curve): rank clicks by predicted conversion; the top-k% of
    # traffic captures what share of all true converters? An advertiser paying only for
    # the top-scoring traffic reads this directly ("keep top 10% → retain X% of installs").
    order = np.argsort(-scores)
    y_sorted = np.asarray(y_true)[order]
    for pct in (0.01, 0.05, 0.10):
        k = max(1, int(len(scores) * pct))
        caught = y_sorted[:k].sum() / max(1, y_sorted.sum())
        results[name][f"converters_in_top_{int(pct*100)}pct"] = round(float(caught), 3)
    print(f"  {name:10s}  ROC-AUC={roc:.4f}  PR-AUC={ap:.4f}")
    return roc, ap


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=None, help="path to real TalkingData CSV (optional)")
    args = ap.parse_args()

    df = load(args.data)
    pos_rate = df["is_attributed"].mean()
    print(f"Positive (download) rate: {pos_rate*100:.3f}%  →  invalid/non-converting: {100-pos_rate*100:.3f}%")

    print("Engineering features...")
    df = add_features(df)
    train, test = temporal_split(df)
    Xtr, ytr = train[FEATURE_COLS].values, train["is_attributed"].values
    Xte, yte = test[FEATURE_COLS].values, test["is_attributed"].values
    print(f"Train {Xtr.shape}  Test {Xte.shape}  (temporal split)")

    results, curves = {}, {}

    # Baseline 1 — logistic regression (linear, scaled, class-balanced).
    logit = make_pipeline(StandardScaler(),
                          LogisticRegression(max_iter=1000, class_weight="balanced"))
    logit.fit(Xtr, ytr)
    s_logit = logit.predict_proba(Xte)[:, 1]
    evaluate("logreg", yte, s_logit, results)
    curves["logreg"] = s_logit

    # Baseline 2 — gradient-boosted trees (captures the non-linear velocity interactions).
    hgb = HistGradientBoostingClassifier(max_iter=300, learning_rate=0.1,
                                         max_leaf_nodes=31, random_state=0)
    hgb.fit(Xtr, ytr)
    s_hgb = hgb.predict_proba(Xte)[:, 1]
    evaluate("hgb", yte, s_hgb, results)
    curves["hgb"] = s_hgb

    # A trivial reference: rank by raw IP click-count only (the single most-cited feature).
    s_cnt = test["cnt_ip"].values.astype(float)
    evaluate("cnt_ip_only", yte, -s_cnt, results)  # more clicks → less likely to convert

    # Feature importance (permutation on the better model, on a test subsample).
    sub = np.random.default_rng(0).choice(len(Xte), size=min(20000, len(Xte)), replace=False)
    imp = permutation_importance(hgb, Xte[sub], yte[sub], scoring="average_precision",
                                 n_repeats=5, random_state=0)
    importances = sorted(zip(FEATURE_COLS, imp.importances_mean), key=lambda x: -x[1])
    results["top_features"] = [{"feature": f, "importance": round(float(v), 4)} for f, v in importances]
    print("  Top features:", ", ".join(f"{f}" for f, _ in importances[:5]))

    (OUT / "metrics.json").write_text(json.dumps(results, indent=2))
    print(f"\nWrote {OUT/'metrics.json'}")

    # Plots -----------------------------------------------------------------
    plt.figure(figsize=(5, 4))
    for name, s in curves.items():
        pr, rc, _ = precision_recall_curve(yte, s)
        plt.plot(rc, pr, label=f"{name} (PR-AUC={results[name]['pr_auc']})")
    plt.axhline(pos_rate, ls="--", c="grey", lw=1, label=f"chance ({pos_rate*100:.2f}%)")
    plt.xlabel("Recall"); plt.ylabel("Precision"); plt.title("Precision–Recall (test)")
    plt.legend(fontsize=8); plt.tight_layout(); plt.savefig(OUT / "pr_curve.png", dpi=120)

    # Cumulative gains — the decision-facing "money chart": ranking traffic by predicted
    # quality, what share of true converters is captured in the top X% of traffic?
    plt.figure(figsize=(5, 4))
    for name, s in curves.items():
        order = np.argsort(-s)
        y_sorted = np.asarray(yte)[order]
        gains = np.cumsum(y_sorted) / max(1, y_sorted.sum())
        x = np.arange(1, len(gains) + 1) / len(gains)
        plt.plot(x, gains, label=f"{name}")
    plt.plot([0, 1], [0, 1], ls="--", c="grey", lw=1, label="random")
    plt.xlabel("Fraction of traffic (ranked by predicted quality)")
    plt.ylabel("Share of true converters captured")
    plt.title("Cumulative gains (test)")
    plt.legend(fontsize=8); plt.tight_layout(); plt.savefig(OUT / "gains_curve.png", dpi=120)

    plt.figure(figsize=(5, 4))
    feats = [f for f, _ in importances][::-1]
    vals = [v for _, v in importances][::-1]
    plt.barh(feats, vals, color="#6366f1")
    plt.xlabel("Permutation importance (Δ PR-AUC)"); plt.title("Feature importance (HGB)")
    plt.tight_layout(); plt.savefig(OUT / "feature_importance.png", dpi=120)
    print(f"Wrote {OUT/'pr_curve.png'} and {OUT/'feature_importance.png'}")


if __name__ == "__main__":
    main()
