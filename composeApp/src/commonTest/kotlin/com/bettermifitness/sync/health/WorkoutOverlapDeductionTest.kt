package com.bettermifitness.sync.health

import com.bettermifitness.sync.data.api.ActiveCaloriesSample
import com.bettermifitness.sync.data.api.DistanceSample
import com.bettermifitness.sync.data.api.StepsRecord
import com.bettermifitness.sync.data.api.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Overlap correction for issue #51: hourly streams already contain workout-period
 * values, so each workout's totals are subtracted from the buckets it overlaps
 * while per-workout records stay untouched.
 */
class WorkoutOverlapDeductionTest {

    private val hour = 1_699_999_200L // hour-aligned: hour % 3600 == 0

    private fun workout(
        start: Long,
        end: Long,
        steps: Int? = null,
        meters: Double? = null,
        kcal: Double? = null,
    ) = WorkoutSession(
        startTime = start,
        endTime = end,
        activityType = "running",
        totalSteps = steps,
        distanceMeters = meters,
        caloriesKcal = kcal,
    )

    @Test
    fun steps_singleHour_deductsFullOverlap() {
        val out = HealthDataNormalizer.subtractWorkoutSteps(
            listOf(StepsRecord(date = hour.toString(), steps = 10_000)),
            listOf(workout(hour + 600, hour + 2_400, steps = 2_000)),
        )
        assertEquals(1, out.size)
        assertEquals(8_000, out[0].steps)
        assertTrue(out[0].workoutAdjusted)
        // Hourly remainder + workout detail still equals the Mi daily total.
        assertEquals(10_000, out[0].steps + 2_000)
    }

    @Test
    fun steps_splitAcrossHours_proportionally() {
        val out = HealthDataNormalizer.subtractWorkoutSteps(
            listOf(
                StepsRecord(date = hour.toString(), steps = 6_000),
                StepsRecord(date = (hour + 3_600).toString(), steps = 4_000),
            ),
            listOf(workout(hour + 1_800, hour + 5_400, steps = 3_000)),
        )
        assertEquals(4_500, out[0].steps)
        assertEquals(2_500, out[1].steps)
        assertTrue(out.all { it.workoutAdjusted })
        assertEquals(6_000 + 4_000, out.sumOf { it.steps } + 3_000)
    }

    @Test
    fun steps_noOverlap_leavesRecordsUntouched() {
        val records = listOf(StepsRecord(date = hour.toString(), steps = 500))
        val out = HealthDataNormalizer.subtractWorkoutSteps(
            records,
            listOf(workout(hour + 3_600, hour + 5_400, steps = 1_000)),
        )
        assertEquals(records, out)
        assertFalse(out[0].workoutAdjusted)
    }

    @Test
    fun steps_fullHourWorkout_floorsAtOne() {
        val out = HealthDataNormalizer.subtractWorkoutSteps(
            listOf(StepsRecord(date = hour.toString(), steps = 2_000)),
            listOf(workout(hour, hour + 3_600, steps = 2_000)),
        )
        // Floor keeps the record alive so the correction overwrites inflated history.
        assertEquals(1, out[0].steps)
        assertTrue(out[0].workoutAdjusted)
    }

    @Test
    fun steps_ignoresEmptyAndInvertedWorkouts() {
        val records = listOf(StepsRecord(date = hour.toString(), steps = 500))
        val noisy = listOf(
            workout(hour, hour + 600),
            workout(hour, hour + 600, steps = 0),
            workout(hour + 600, hour),
        )
        assertEquals(records, HealthDataNormalizer.subtractWorkoutSteps(records, noisy))
        assertEquals(records, HealthDataNormalizer.subtractWorkoutSteps(records, emptyList()))
        assertEquals(
            emptyList(),
            HealthDataNormalizer.subtractWorkoutSteps(emptyList(), noisy),
        )
    }

    @Test
    fun steps_handlesMillisTimestamps() {
        val out = HealthDataNormalizer.subtractWorkoutSteps(
            listOf(StepsRecord(date = hour.toString(), steps = 5_000)),
            listOf(workout(hour * 1_000 + 600_000, hour * 1_000 + 2_400_000, steps = 1_000)),
        )
        assertEquals(4_000, out[0].steps)
        assertTrue(out[0].workoutAdjusted)
    }

    @Test
    fun distance_deductsOverlap() {
        val out = HealthDataNormalizer.subtractWorkoutDistance(
            listOf(DistanceSample(startTime = hour, endTime = hour + 3_599, meters = 8_000.0)),
            listOf(workout(hour + 600, hour + 2_400, meters = 2_000.0)),
        )
        assertEquals(6_000.0, out[0].meters, 1e-9)
        assertTrue(out[0].workoutAdjusted)
    }

    @Test
    fun calories_deductsOverlap() {
        val out = HealthDataNormalizer.subtractWorkoutCalories(
            listOf(ActiveCaloriesSample(startTime = hour, endTime = hour + 3_599, kilocalories = 400.0)),
            listOf(workout(hour + 600, hour + 2_400, kcal = 100.0)),
        )
        assertEquals(300.0, out[0].kilocalories, 1e-9)
        assertTrue(out[0].workoutAdjusted)
    }
}
