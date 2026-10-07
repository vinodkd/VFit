#!/usr/bin/env python3
"""Synthetic demo data for screenshots / the home page GIF (no real person's data).
Writes inputs.csv, glucose.csv, config.csv in VFit import format, from 1 January to yesterday:
steady loss that slows into a plateau, occasional fasting days, two strength sessions a week, weekly BP.
Usage: python3 tools/make_demo_data.py <outdir>"""
import csv, datetime as dt, math, os, random, sys

out = sys.argv[1] if len(sys.argv) > 1 else "demo_csv"
os.makedirs(out, exist_ok=True)
rng = random.Random(42)
start = dt.date(dt.date.today().year, 1, 1)
end = dt.date.today() - dt.timedelta(days=1)

W, rho, b = 96.0, 7700.0, 22.0
a = 2700 - b * W                     # TDEE intercept
water = 0.0
inputs, glucose = [], []
d, i = start, 0
while d <= end:
    adapt = -120 * min(max((i - 120) / 90, 0), 1)                 # some adaptation later in the year
    tdee = a + adapt + b * W
    fasting = rng.random() < 0.08
    plan = 2150 if i < 150 else 2400                               # intake creeps up -> plateau
    if fasting:
        kcal = 0 if rng.random() < 0.7 else 400
    elif rng.random() < 0.12:
        kcal = None                                                # not entered
    else:
        kcal = round(rng.gauss(plan, 250) / 50) * 50
        if rng.random() < 0.06:
            kcal += 1200                                           # a feast day
    eaten = kcal if kcal is not None else plan
    W += (eaten - tdee) / rho
    water = 0.7 * water + rng.gauss(0, 0.25)
    weight = round(W + water + rng.gauss(0, 0.2), 1) if rng.random() > 0.05 else None
    waist = round(104 - 1.3 * (96 - W) + rng.gauss(0, 0.4), 1) if rng.random() > 0.2 else None
    squat = round(110 + 10 * math.tanh(i / 80) + rng.gauss(0, 2), 1) if d.weekday() in (0, 3) and i > 20 else None
    bp = (round(rng.gauss(128 - 6 * min(i / 200, 1), 4)), round(rng.gauss(84 - 4 * min(i / 200, 1), 3)), round(rng.gauss(70, 4))) \
        if d.weekday() == 6 else (None, None, None)
    inputs.append([d.isoformat(), weight or "", waist or "", "" if kcal is None else kcal, "", "", "1" if fasting else "",
                   bp[0] or "", bp[1] or "", bp[2] or "", squat or ""])
    glucose.append([d.isoformat(), "07:00", "fasting", 0, "", round(rng.gauss(112 - 8 * min(i / 200, 1), 7))])
    if not fasting and rng.random() < 0.6:
        glucose.append([d.isoformat(), "10:00", "2h_post_meal", 1, "", round(rng.gauss(135, 15))])
    if not fasting and rng.random() < 0.4:
        glucose.append([d.isoformat(), "15:00", "2h_post_meal", 4, "", round(rng.gauss(140, 15))])
    d += dt.timedelta(days=1)
    i += 1

with open(os.path.join(out, "inputs.csv"), "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["date", "weight_kg", "waist_cm", "calories", "activity", "grip", "fasting", "bp_sys", "bp_dia", "pulse", "e1rm_squat"])
    w.writerows(inputs)
with open(os.path.join(out, "glucose.csv"), "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["date", "time", "kind", "slot", "meal", "mg_dl"])
    w.writerows(glucose)
with open(os.path.join(out, "config.csv"), "w", newline="") as f:
    w = csv.writer(f)
    w.writerows([["setting", "value"], ["height", 178], ["goal_weight", 82], ["age", 45],
                 ["target_exercise", 15], ["target_diet", 50], ["target_fasted", 10], ["target_lived", 25]])
print(f"{len(inputs)} days, {len(glucose)} glucose readings -> {out}/")
