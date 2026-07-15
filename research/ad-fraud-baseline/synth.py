"""
Synthetic click-stream faithful to the TalkingData AdTracking Fraud Detection schema
(ip, app, device, os, channel, click_time, is_attributed).

Why synthetic: the real Kaggle set is ~200M rows / ~7GB behind an auth wall. This
generator reproduces the *mechanics that matter* for the task so the whole pipeline —
features, baselines, evaluation — runs end to end now, and swaps to the real CSV with
`experiment.py --data train.csv` (identical column names).

Modelled mechanics (the ones the Kaggle winners' features exploited):
  * A minority of IPs are click farms / bots: very high click volume, near-zero
    downloads (is_attributed≈0) — the classic "invalid traffic" signature.
  * Genuine users click far less and convert at a low but non-zero rate.
  * Bot clicks arrive in tight bursts, so time-to-next-click is short — a strong signal.
  * The label is extremely imbalanced (~0.2% positive), like the real data.
"""
from __future__ import annotations
import numpy as np
import pandas as pd


def generate_synthetic(n_rows: int = 400_000, seed: int = 7) -> pd.DataFrame:
    rng = np.random.default_rng(seed)

    n_ips = 40_000
    fraud_ip_frac = 0.03                       # 3% of IPs are click farms
    n_fraud_ips = int(n_ips * fraud_ip_frac)
    fraud_ips = set(range(n_fraud_ips))        # ids [0, n_fraud_ips) are fraudulent

    # Fraud IPs carry a hugely disproportionate share of *clicks* (that's the point of
    # a click farm) even though they're a small share of *IPs*.
    ip_ids = np.arange(n_ips)
    weights = np.ones(n_ips)
    weights[:n_fraud_ips] = 60.0               # each fraud IP ~60x the click volume
    weights = weights / weights.sum()
    row_ip = rng.choice(ip_ids, size=n_rows, p=weights)

    is_fraud_ip = np.isin(row_ip, list(fraud_ips))

    # Categorical fields — bots concentrate on a few apps/channels/devices.
    n_apps = 500
    app = np.where(is_fraud_ip, rng.integers(1, 8, n_rows), rng.integers(1, n_apps, n_rows))
    channel = np.where(is_fraud_ip, rng.integers(1, 15, n_rows), rng.integers(1, 200, n_rows))
    device = np.where(is_fraud_ip, rng.integers(1, 4, n_rows), rng.integers(1, 100, n_rows))
    os = np.where(is_fraud_ip, rng.integers(1, 6, n_rows), rng.integers(1, 60, n_rows))

    # Click times over 3 days. Fraud clicks cluster (bursts) → shorter inter-click gaps.
    day_ms = 24 * 3600 * 1000
    base = rng.integers(0, 3 * day_ms, n_rows)
    # bots get a tiny jitter (tight bursts), humans a wide spread
    jitter = np.where(is_fraud_ip, rng.integers(0, 2000, n_rows), rng.integers(0, 8 * 3600 * 1000, n_rows))
    click_ms = (base + jitter).astype(np.int64)
    click_time = pd.to_datetime(click_ms, unit="ms", origin="2017-11-06")

    # Conversion is driven by *observable* structure (as in the real data, where these
    # features give ROC-AUC ~0.98), not unobservable noise:
    #   * fraud/bot IPs almost never convert;
    #   * apps differ enormously in intent — a per-app conversion multiplier (heavy-tailed);
    #   * later hours convert slightly better (mild diurnal effect).
    app_mult = rng.lognormal(mean=0.0, sigma=1.1, size=n_apps + 1)   # per-app propensity
    hour = pd.to_datetime(click_ms, unit="ms", origin="2017-11-06").hour.values
    diurnal = 0.8 + 0.4 * (hour / 23.0)
    p_attr = np.where(
        is_fraud_ip,
        0.0003,
        np.clip(0.012 * app_mult[app] * diurnal, 0.0, 0.6),
    )
    is_attributed = (rng.random(n_rows) < p_attr).astype(np.int8)

    df = pd.DataFrame({
        "ip": row_ip.astype(np.int32),
        "app": app.astype(np.int16),
        "device": device.astype(np.int16),
        "os": os.astype(np.int16),
        "channel": channel.astype(np.int16),
        "click_time": click_time,
        "is_attributed": is_attributed,
    }).sort_values("click_time").reset_index(drop=True)
    return df


if __name__ == "__main__":
    d = generate_synthetic()
    print(d.shape, "positive rate = %.4f%%" % (100 * d.is_attributed.mean()))
    d.to_csv("synthetic_clicks.csv", index=False)
    print("wrote synthetic_clicks.csv")
