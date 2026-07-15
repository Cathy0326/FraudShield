"""
Feature engineering for click-fraud detection.

These are the canonical TalkingData features: velocity/aggregation counts over IP and
IP-combinations, plus the single strongest signal in this problem — time to the *next*
click from the same IP (bots fire rapidly). Everything here is computable in a real-time
system from a short sliding window, which matters for deployment (and mirrors the
Redis-window approach in the FraudShield engine).
"""
from __future__ import annotations
import numpy as np
import pandas as pd

GROUP_COUNTS = [
    ["ip"],
    ["ip", "app"],
    ["ip", "device", "os"],
    ["app", "channel"],
]


def add_features(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    df["click_time"] = pd.to_datetime(df["click_time"])
    df["hour"] = df["click_time"].dt.hour.astype(np.int8)

    # Aggregation counts: how much activity shares this key (bots inflate these).
    for cols in GROUP_COUNTS:
        name = "cnt_" + "_".join(cols)
        df[name] = df.groupby(cols)["click_time"].transform("size").astype(np.int32)

    # Distinct-channel / distinct-app spread per IP (farms hit few; humans varied).
    df["ip_nunique_app"] = df.groupby("ip")["app"].transform("nunique").astype(np.int16)
    df["ip_nunique_channel"] = df.groupby("ip")["channel"].transform("nunique").astype(np.int16)

    # Time to next / previous click from the same IP (seconds). The dominant signal.
    df = df.sort_values(["ip", "click_time"])
    grp = df.groupby("ip")["click_time"]
    df["next_click_sec"] = (grp.shift(-1) - df["click_time"]).dt.total_seconds()
    df["prev_click_sec"] = (df["click_time"] - grp.shift(1)).dt.total_seconds()
    df = df.sort_values("click_time").reset_index(drop=True)

    # Missing next/prev (first/last click for an IP) → large sentinel (i.e. "isolated").
    df["next_click_sec"] = df["next_click_sec"].fillna(1e6)
    df["prev_click_sec"] = df["prev_click_sec"].fillna(1e6)
    return df


FEATURE_COLS = [
    "app", "device", "os", "channel", "hour",
    "cnt_ip", "cnt_ip_app", "cnt_ip_device_os", "cnt_app_channel",
    "ip_nunique_app", "ip_nunique_channel",
    "next_click_sec", "prev_click_sec",
]
