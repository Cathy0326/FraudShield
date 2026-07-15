# Ad Click-Fraud / Invalid-Traffic Detection — Baseline

A small, reproducible experiment: predict whether a mobile ad click leads to a genuine
install (`is_attributed`) on the **TalkingData AdTracking** schema — a severely
imbalanced, adversarial, time-ordered detection task. See **[REPORT.md](REPORT.md)** for
the write-up and results.

## Run
```
pip install -r requirements.txt
python experiment.py                    # synthetic data faithful to the schema
python experiment.py --data train.csv   # the real Kaggle CSV (identical columns)
```
Produces `metrics.json`, `pr_curve.png`, `feature_importance.png`.

## Files
| File | Purpose |
|---|---|
| `synth.py` | Synthetic click stream reproducing click-farm / conversion mechanics |
| `features.py` | Velocity + aggregation + next-click feature engineering |
| `experiment.py` | Temporal split, baselines (LogReg, gradient boosting), imbalance-aware eval |
| `REPORT.md` | 2–3 page short report |

## Why synthetic
The real TalkingData corpus is ~200M rows / ~7GB behind a Kaggle auth wall. The generator
reproduces the mechanics the task depends on so the full pipeline runs now; swap in the
real CSV with `--data` and every column name matches.
