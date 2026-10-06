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
| Charts | Vico (Compose-native) |
| Model | `:model` Gradle module, pure Kotlin/JVM, no Android dependencies. 5×5 matrix ops written by hand (no library needed) |
| Background work | None in v1 (recomputing is fast enough to run on demand) |
| Min SDK | 26 (Android 8.0); target the latest SDK |
| Tests | JUnit for `:model` (golden tests against Python), Compose UI tests for entry flows |

### Modules
```
:app      UI, ViewModels, navigation
:data     Room entities/DAOs, repository, CSV import/export
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

**settings**: key/value table with the same keys as the workbook's Config sheet (units, sex, age, height, goal_weight, goal_rate, b_kcal_per_kg, energy_density, weight_noise_kg, offset_sedentary, offset_moderate, offset_heavy, adaptation_ref_date), plus glucose_unit (mg/dL or mmol/L).

**Daily calories** = `calories_override` if present, else Σ(kcal × quantity) over meal_entry rows; null if there are neither. A total of 0 counts as missing, which matches the current script.

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
- Use the Python script to produce **golden files**: CSVs of inputs and every output column for (a) `simulate` and (b) optionally a private export of your own tracker workbook (not committed).
- Kotlin unit tests run `:model` on the same inputs and assert matches: weights within 0.05, kcal within 10, flags and strings exact.
- When the model changes, change the Python version first, regenerate the golden files, then port.

## 5. Screens
1. **Today** (home)
   - Weight field with a numeric keypad, empty by default; yesterday's value shown as a hint.
   - Meals: chips for saved meals, most recently used first. Tap adds one, long-press sets the quantity. "+ One-off meal" takes a name and kcal. Shows the day's total kcal.
   - Activity: four-way segmented control.
   - Waist and grip fields, always shown; lift sets on demand.
   - Blood glucose: a "Fasting" field, and a "2 h after" field under each meal logged that day (prefilled time = meal time + 2 h).
   - Date switcher to fill in past days.
2. **Dashboard**: the four Summary sections as cards (Current status incl. adaptation, Forecast, Plateau check, Composition), with the same wording and gating messages as `write_summary()`, plus a Glucose card: 7-day average fasting and 2 h post-meal values.
3. **Charts**: (a) daily weight dots, trend line and a 26-week forecast with its 80% band and the goal line; (b) daily intake, 14-day average, and TDEE with its 80% band; (c) waist and strength index; (d) blood glucose: fasting and 2 h post-meal readings, with their 7-day averages.
4. **History**: list of days showing the key inputs and outputs; tap a day to edit it in the Today screen.
5. **Meals**: manage saved meals (name, kcal, archive).
6. **Settings**: Config values, units, import/export.

## 6. Recompute flow
Any change to entries or settings triggers `repository.recompute()`: load all days, build the input arrays, run `:model`, and cache the outputs in memory (`StateFlow<ModelResult>`). Outputs are not persisted, since they're always derivable. On a cold start the app recomputes once.

## 7. Import / export
- **Import:** CSV with the workbook's input columns (`date, weight, waist, calories, activity, grip, <lift>_load, <lift>_reps…`). Calories go into `calories_override`. A helper script, `tdee_tracker.py export-csv`, writes this CSV from the workbook, resolving formulas the same way `update` does.
- **Export:** the same CSV format, plus meals and glucose CSVs, shared via the Android share sheet.

## 8. Milestones
1. `:model` port and golden tests passing against both datasets.
2. Room schema, CSV import, Today screen with saved meals.
3. Dashboard and Charts.
4. History, Settings, export. Install on your phone for daily use, distributed as a sideloaded APK.
5. (Later) Health Connect and the food database.

## 9. Open questions
- None currently. Glucose is recorded and charted only; it does not feed the TDEE model.
