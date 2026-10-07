package com.vfit.app.data

/** The Today timeline, in order. Meals and exercise entries store the slot id. */
enum class Slot(val id: Int, val label: String, val kind: Kind) {
    MORNING(0, "Morning check-in", Kind.BODY),
    BREAKFAST(1, "Breakfast", Kind.MEAL),
    MORNING_SNACK(2, "Morning snack", Kind.SNACK),
    MORNING_EXERCISE(3, "Morning exercise", Kind.EXERCISE),
    LUNCH(4, "Lunch", Kind.MEAL),
    AFTERNOON_SNACK(5, "Afternoon snack", Kind.SNACK),
    DAY_EXERCISE(6, "Exercise during the day", Kind.EXERCISE),
    DINNER(7, "Dinner", Kind.MEAL),
    EVENING_SNACK(8, "Evening snack", Kind.SNACK),
    END_OF_DAY(9, "End of day", Kind.DAY);

    enum class Kind { BODY, MEAL, SNACK, EXERCISE, DAY }

    val isFood get() = kind == Kind.MEAL || kind == Kind.SNACK

    companion object {
        fun of(id: Int?) = entries.firstOrNull { it.id == id }

        /** Slot for a meal logged without one (before slots existed), from its time; null if no time. */
        fun forMealTime(minutes: Int?): Slot? = when {
            minutes == null -> null
            minutes < 10 * 60 + 30 -> BREAKFAST
            minutes < 12 * 60 -> MORNING_SNACK
            minutes < 14 * 60 + 30 -> LUNCH
            minutes < 17 * 60 + 30 -> AFTERNOON_SNACK
            minutes < 21 * 60 -> DINNER
            else -> EVENING_SNACK
        }

        fun ofMeal(m: MealEntry) = of(m.slot) ?: forMealTime(m.time)
    }
}
