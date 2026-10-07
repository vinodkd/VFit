# TDEE Tracker for Android: design doc

Status: draft, 2026-10-05
Source of truth for the model: `tdee_tracker.py` (`compute()`, lines 315-517)

## 1. Goal
A native Android app that replaces the Excel workflow. It does daily logging (weight, meals, activity, waist, grip, lifts, blood glucose) on the phone and runs the same Kalman-filter TDEE model on-device. It shows the same outputs that the workbook's Summary and Charts sheets show today.

### In scope (v1)
- Daily entry: weight, waist, grip, meals (saved/recent meals with per-meal kcal), activity, lifts, and blood glucose (fasting, plus 2 hours after each meal).
- On-device model, a faithful port of `compute()`.
- Dashboard (the equivalent of the Summary sheet), three charts, and history with editing.
- Settings (the equivalent of the Config sheet).
- One-off import of existing data from the workbook (via CSV), plus CSV export and backup.

### Later
- Health Connect: read weight and steps (activity derived from steps), and read nutrition totals from other apps.
- Food database (USDA FoodData Central generic foods and survey dishes, bundled), barcode scanning via Open Food Facts, and free-text meal entry.
- iOS or web, via Kotlin Multiplatform. The model module is kept platform-free so this stays possible.

### Out of scope
- Accounts, cloud sync, servers. All data stays on the device.
- Meal-size or meal-count noise modelling. The model takes the day's calories as given.

## 2. Tech stack
| Concern | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Storage | Room (SQLite) |
| Architecture | Single activity, MVVM; ViewModels expose `StateFlow` |
| Charts | Own Compose `Canvas` chart (no library) with a touch scrubber |
| Theme | VFit palette (navy + orange from the logo), light and dark; one accent colour per kind of timeline item |
| Model | `:model` Gradle module, pure Kotlin/JVM, no Android dependencies. 5×5 matrix ops written by hand (no library needed) |
| Background work | None in v1 (recomputing is fast enough to run on demand) |
| Min SDK | 26 (Android 8.0); target the latest SDK |
| Tests | JUnit for `:model` (golden tests against Python), Compose UI tests for entry flows |

### Modules
```
:app      UI, ViewModels, navigation; data layer in package com.vfit.app.data (Room, repository, CSV)
:model    Kalman filter + derived outputs (pure Kotlin, unit-tested)
```

## 3. Data model (Room)

**day_entry**: one row per calendar day
| column | type | notes |
|---|---|---|
| date | LocalDate (PK) | |
| weight | Double? | stored in kg |
| waist | Double? | stored in cm |
| activity | Int? | 0 sedentary, 1 light, 2 moderate, 3 heavy; null = unknown |
| grip | Double? | as entered |
| calories_override | Int? | if set, used instead of the sum of meal entries (imported historical totals, quick totals) |
| note | String? | |
| fasting | Boolean | fasting day (v4); meals can still be logged; with nothing logged the day's intake is 0 |
| bp_sys, bp_dia, pulse | Double? | blood pressure (mmHg) and pulse (bpm), v5 |

**meal_template**: saved meals
| column | type | notes |
|---|---|---|
| id | Long (PK) | |
| name | String | e.g. "Usual breakfast" |
| kcal | Int | |
| archived | Boolean | hidden from pickers, kept for history |

**meal_entry**: meals eaten on a day
| column | type | notes |
|---|---|---|
| id | Long (PK) | |
| date | LocalDate (FK → day_entry) | |
| template_id | Long? | null for a one-off meal |
| name | String | copied from the template at log time |
| kcal | Int | copied from the template at log time, so editing a template doesn't rewrite history |
| quantity | Double | default 1.0 (e.g. 0.5 = half portion) |
| time | Int? | minutes after midnight when logged (today only) |
| slot | Int? | timeline slot (see Screens); null for meals logged before v2, placed by time |

**exercise_entry** (v2): exercise by timeline slot. Recorded only; the model uses the day's activity level.
| column | type | notes |
|---|---|---|
| id | Long (PK) | |
| date | LocalDate | |
| slot | Int | morning exercise or exercise during the day |
| description | String | e.g. "walk" |
| minutes | Int? | |

**lift_set**: top working set per lift per day
| column | type | notes |
|---|---|---|
| date | LocalDate | PK with `lift` |
| lift | String | e.g. "squat" |
| load | Double | |
| reps | Int | e1RM = load if reps = 1, else load·(1 + reps/30) |

**glucose_reading**: blood glucose
| column | type | notes |
|---|---|---|
| id | Long (PK) | |
| date | LocalDate | |
| time | LocalTime | when measured |
| kind | Int | 0 fasting, 1 two hours after a meal |
| meal_entry_id | Long? | the meal it follows (kind 1 only) |
| value | Double | stored in mg/dL; displayed in mg/dL or mmol/L (÷ 18.016) per settings |
| slot | Int? | meal slot a post-meal reading follows (v5), so imported readings need no logged meal |

**settings**: key/value table with the same keys as the workbook's Config sheet (units, sex, age, height, goal_weight, goal_rate, b_kcal_per_kg, energy_density, weight_noise_kg, offset_sedentary, offset_moderate, offset_heavy, adaptation_ref_date), plus glucose_unit (mg/dL or mmol/L).

**Daily calories** = `calories_override` if present, else Σ(kcal × quantity) over meal_entry rows; else 0 on a fasting day; else null (not logged). 0 is a real intake in the model (and in the script).

**Gap filling** (before the model runs): a past day with nothing logged (not today, not fasting) gets intake interpolated between the nearest logged non-fasting days if both are within 7 days, otherwise the `assumed_intake` setting (1,600 = 2 × 800 kcal meals). Charts show these as "assumed".

**Units:** stored internally in metric. Converted for display per the `units` setting (1 lb = 0.45359237 kg, 1 in = 2.54 cm).

## 4. Model specification (port of `compute()`)
Each day's outputs use only data up to that day. The app recomputes the full history on every change. That costs O(days) work with 5×5 matrices, which is under 50 ms for years of data. The workbook's incremental hashing is not ported.

### 4.1 Filter
State `x = [W, a, o_sed, o_mod, o_heavy]`; the offset for a light day is 0.
TDEE(day) = a + b·W + offset[activity].

Constants: `b` = b_kcal_per_kg (22), `rho` = energy_density (7700), `R` = weight_noise_kg² (0.36).

Initialisation, on the first day with a weight:
- `x0 = [W0, 1.4·Mifflin(W0, height, age, sex) − b·W0, off_sed, off_mod, off_heavy]`
- Mifflin = 10·W + 6.25·h_cm − 5·age + (5 male / −161 female)
- `P0 = diag(R, 350², 150², 150², 200²)`
- `Q_base = diag(0.03², 12², 1, 1, 1)`

Predict step from day j to day j+1, using day j's inputs:
- **Intake.** If calories are missing:
  - If at least 3 days in the window j−13…j have calories, use their mean and add (450/rho)² to Q[0,0].
  - Otherwise this is a **no-info day**: add (600/rho)² to Q[0,0] and predict no change (described below).
- **Activity.**
  - sedentary, moderate or heavy: select that offset, i.e. e[k] = 1.
  - light: no offset.
  - unknown: no offset, and add (150/rho)² to Q[0,0].
- **Transition.** `F = I` except row 0: `F[0,0] = 1 − b/rho`, `F[0,1] = −1/rho`, `F[0,2+k] = −e[k]/rho`.
- **No-info day.** Row 0 of F becomes `[1,0,0,0,0]` and intake = 0. This decouples W from TDEE and prevents the TDEE ratchet bug.
- Then `x = F·x + [intake/rho, 0,0,0,0]` and `P = F·P·Fᵀ + Q`.

Update step, on days with a weight: scalar Kalman update with `H = [1,0,0,0,0]` and measurement noise R.

### 4.2 Derived outputs (day i; age = days since the first weight)
| Output | Rule |
|---|---|
| trend_weight | x[0] |
| weekly_rate | age ≥ 14: (x[0] − x₍ᵢ₋₁₄₎[0]) / 2 |
| data_coverage | age ≥ 13: days in the last 14 with both weight and calories ÷ 14 |
| intake_avg_14d | mean calories over the last 14 days if at least 7 days are logged |
| activity mix `fr` | share of the last 14 days that are sedentary / moderate / heavy (unknown days count in the denominator) |
| tdee_est | gated by **tdee_ok** = age ≥ 20, ≥ 10 weights so far, ≥ 10 calorie days in the last 28. Value = [b, 1, fr]·x; sd = √(h·P·hᵀ) |
| tdee_low / high | tdee ∓ 1.2816·sd (80% range) |
| tdee_confidence | sd < 100 High, < 200 Medium, otherwise Low |
| deficit | tdee − intake_avg_14d |
| settling_weight | requires tdee_ok, age ≥ 27, intake_avg. base = [0,1,fr]·x; ws = (intake_avg − base)/b; sd_s = √(hs·P·hsᵀ)/b; range ws ∓ 1.2816·sd_s |
| proj_4w/12w/26w | ws + (W − ws)·exp(−b·d/rho) for d = 28, 84, 182 |
| target_intake | if W > goal: tdee − goal_rate·rho/7; otherwise tdee |
| goal_date | if W > goal and ws < goal: today + (rho/b)·ln((W − ws)/(goal − ws)) days; if ws ≥ goal: "not at current intake"; if W ≤ goal: "reached" |
| waist_trend | ≥ 3 readings in the last 28 days: least-squares slope × 7 |
| waist_per_wt_lost | ≥ 4 readings in total and lost ≥ 1.5 kg: (mean of the first 2 − mean of the last 2) ÷ lost |
| strength_index | for each measure (grip, each lift's e1RM): mean over the last 28 days ÷ mean of its first 2 readings − 1, averaged across measures |
| strength_flag | index change over 28 days < −5% and trend change over 28 days < −0.5 kg |
| plateau_flag | requires age ≥ 34, a goal, goal_rate > 0, W > goal + 0.5. rate21 = (W − W₍ᵢ₋₂₁₎)/3; thresh = max(0.1, 0.25·goal_rate). If rate21 > −thresh: REGAINING if rate21 > thresh, otherwise PLATEAU |
| adaptation | from the reference day onward (Config `adaptation_ref_date`, else the first tdee_ok day): a − a_ref. Equals the TDEE change at the same activity minus b·ΔW; negative = burning less than weight lost explains |
| plateau_cause | in order: waist_trend < −0.25 or strength up > 2% over 21 days → "possible water masking"; deficit unknown → "can't tell"; deficit < 200 → "deficit closed"; otherwise "unclear" |

Display rounding as in the script: kcal values to the nearest 10; weights to 0.1.

### 4.3 Verification
- Use the Python script to produce **golden files**: CSVs of inputs and every output column for (a) `simulate` (committed) and (b) optionally a private export of your own tracker workbook (not committed; passed to the test with `-Pgolden.extra=<dir>`).
- Kotlin unit tests run `:model` on the same inputs and assert matches: weights within 0.05, kcal within 10, flags and strings exact.
- When the model changes, change the Python version first, regenerate the golden files, then port.

## 5. Screens
Bottom tabs: Today, Do next, Trends, Settings. (A History tab was dropped: the Today date picker and CSV export cover it.)

1. **Today**: the day as a timeline, like a to-do list. Each step is a tinted card on a vertical line; its dot fills in once logged.
   1. Morning check-in: "Fasting today" switch, then weight, fasting glucose, waist, grip (optional), and under
      "More" blood pressure and pulse, saved together.
   2. Breakfast → 3. Morning snack → 4. Morning exercise → 5. Lunch → 6. Afternoon snack → 7. Exercise during the day →
      8. Dinner → 9. Evening snack.
      - Food steps: "Not logged yet" until something is logged. "Log breakfast" opens a dialog: a search-as-you-type
        dropdown of saved meals (most-logged in that slot first) or a new meal (name, kcal, optionally saved), amount
        and time. Logged meals show a ✓, −/+ quantity and delete (with Undo).
      - After any food step with something logged (or an imported reading): a separate "Blood sugar 2 h after …" step.
      - Exercise steps: "Activity" (dropdown of past exercises, pre-filling the last duration) and "Strength set"
        (dropdown of past lifts, pre-filling the last load and reps).
   10. End of day: overall activity level (feeds the model) and the imported-total notice.
   - Header: date with previous/next and a calendar date picker; pills for kcal eaten, trend weight and TDEE.
2. **Do next**: how you've managed so far and what to change: just two cards.
   - Year at a glance: from 1 January (or the `period_start` setting) to today, each day coloured by what won:
     fasted, else lived (ate more than that day's TDEE), else exercise or diet depending on whether estimated exercise
     calories (minutes × 4 net METs; 30 min for a strength set without a duration) cover more of the deficit than
     eating less did. Counts and % per kind; with `target_*` settings (share of days, %), days left (or over) out of
     a year from the start.
   - Next step: eat less / move more / hold / keep logging, with numbers.
3. **Trends**: status tiles with sparklines (trend weight, TDEE, intake, adaptation, settling weight, goal, waist,
   glucose split by meal, BP, strength), any plateau/regain banner, "Show details" (the full `write_summary()` text),
   then the charts: weight trend + forecast, TDEE vs intake (assumed intake shown separately), adaptation, waist,
   strength, blood pressure + pulse, blood glucose. Touch and slide on any chart to pin a date (or date and time, for
   glucose): a guide line and dots mark it, and a readout above the chart lists every series' value there.
4. **Meals** (from Today or Settings): manage saved meals (name, kcal, hide).
5. **Settings**: Config values, units, glucose unit, assumed intake for unlogged days, the Do next period start and
   targets, import/export.

## 6. Recompute flow
Any change to entries or settings triggers `repository.recompute()`: load all days, build the input arrays, run `:model`, and cache the outputs in memory (`StateFlow<ModelResult>`). Outputs are not persisted, since they're always derivable. On a cold start the app recomputes once.

## 7. Import / export
- **Vital data:** `tdee_tracker.py vital-to-csv` converts the program's export into inputs.csv (weight, waist, calories, fasting, BP, pulse), glucose.csv (fasting + 2 h after breakfast/lunch/dinner, with slots) and config.csv (height from BMI). An imported file is the source for its dates: its columns overwrite those days; other dates are untouched.
- **Import:** CSV with the workbook's input columns (`date, weight, waist, calories, activity, grip, <lift>_load, <lift>_reps…`). Calories go into `calories_override`. A helper script, `tdee_tracker.py export-csv`, writes this CSV from the workbook, resolving formulas the same way `update` does.
- **Export:** the same CSV format, plus meals, glucose and exercise CSVs, shared via the Android share sheet.

## 8. Milestones
1. `:model` port and golden tests passing against both datasets.
2. Room schema, CSV import, Today screen with saved meals.
3. Dashboard and Charts.
4. Settings, export. Install on your phone for daily use, distributed as a sideloaded APK.
5. (Later) Health Connect and the food database.

## 9. Open questions
- None currently. Glucose is recorded and charted only; it does not feed the TDEE model.
