package com.noop.analytics

import org.json.JSONArray

/**
 * Decode a sleep session's `stagesJSON` into stage MINUTE totals, and aggregate a night's blocks into
 * the sleep-derived daily fields. Pure + deterministic, so the daily-aggregate recompute that honors a
 * user's bed/wake-time edit can run off the stored (reshaped) stages — no raw streams needed.
 *
 * Faithful Kotlin port of StrandAnalytics/Sources/StrandAnalytics/SleepStageTotals.swift (the iOS
 * `dailyAggregateHonoringEdits` seam from PR #395), adapted to Android's two stagesJSON shapes:
 *   - on-device COMPUTED (what the IntelligenceEngine writes via [AnalyticsEngine.encodeStages]):
 *     `[{start,end,stage}]` — per-segment unix SECONDS spans;
 *   - IMPORTED (WhoopCsvImporter.stagesJson): `[{stage,min}]` — per-stage MINUTE totals.
 * The on-device stager calls awake "wake"; the importer "awake" — both map to `awake`.
 *
 * The edit/recompute path only ever feeds the COMPUTED (`-noop`) source's `[{start,end,stage}]` stages
 * here (the daily override is computed-source-only, mirroring iOS scope), but [minutes] handles both
 * shapes so the helper is a complete twin of the Swift one and is robust to either input.
 */
object SleepStageTotals {

    /** Stage minute totals for one session. `asleep` = light+deep+rem; `inBed` = asleep+awake. */
    data class Minutes(
        var awake: Double = 0.0,
        var light: Double = 0.0,
        var deep: Double = 0.0,
        var rem: Double = 0.0,
    ) {
        val asleep: Double get() = light + deep + rem
        val inBed: Double get() = asleep + awake
    }

    /**
     * The sleep-derived daily fields for a night, or null if nothing decodes. `efficiency` is
     * asleep / in-bed (TST / Σ stage minutes) in [0,1]. For the segment stages noop stores (which TILE
     * the window), Σ stage minutes equals the clock span, so this coincides with the SleepStager's
     * TST/(end−start). Mirrors Swift `SleepStageTotals.DailySleep`.
     */
    data class DailySleep(
        val totalSleepMin: Double,
        val efficiency: Double,
        val deepMin: Double,
        val remMin: Double,
        val lightMin: Double,
    )

    /**
     * Stage minutes for one session's `stagesJSON`, or null if it decodes to nothing usable.
     * Handles both Android shapes — `[{start,end,stage}]` (seconds spans) and `[{stage,min}]`
     * (minute totals). Mirrors Swift `minutes(fromStagesJSON:)`.
     */
    fun minutes(stagesJSON: String?): Minutes? {
        val json = stagesJSON ?: return null
        val arr = try {
            JSONArray(json)
        } catch (_: Throwable) {
            return null
        }
        val m = Minutes()
        for (i in 0 until arr.length()) {
            val seg = arr.optJSONObject(i) ?: continue
            val name = seg.optString("stage", "")
            // Per-segment SECONDS span (computed/edited) → minutes; else a direct minute total (imported).
            val mins = when {
                seg.has("start") && seg.has("end") -> {
                    val s = seg.optLong("start")
                    val e = seg.optLong("end")
                    if (e > s) (e - s) / 60.0 else continue
                }
                seg.has("min") -> seg.optDouble("min", 0.0)
                else -> continue
            }
            if (mins <= 0.0) continue
            when (name) {
                "wake", "awake" -> m.awake += mins
                "light" -> m.light += mins
                "deep" -> m.deep += mins
                "rem" -> m.rem += mins
                else -> continue
            }
        }
        return if (m.inBed > 0.0) m else null
    }

    /** The night's daily sleep aggregate over these blocks' `stagesJSON`, or null if none decode.
     *  Mirrors Swift `dailyAggregate`. */
    fun dailyAggregate(stagesJSONs: List<String?>): DailySleep? {
        val total = Minutes()
        var any = false
        for (j in stagesJSONs) {
            val mm = minutes(j) ?: continue
            total.awake += mm.awake
            total.light += mm.light
            total.deep += mm.deep
            total.rem += mm.rem
            any = true
        }
        if (!any || total.inBed <= 0.0) return null
        return DailySleep(
            totalSleepMin = total.asleep,
            efficiency = total.asleep / total.inBed,
            deepMin = total.deep,
            remMin = total.rem,
            lightMin = total.light,
        )
    }

    /** Result of [dailyAggregateHonoringEdits]: the aggregate plus whether an edit actually applied. */
    data class HonoredAggregate(val sleep: DailySleep, val editApplied: Boolean)

    /**
     * The night's daily sleep aggregate, substituting any USER-EDITED block for its detected twin before
     * summing. [detected] is the auto-detected blocks (their stable startTs + stages); [edited] maps a
     * block's startTs → its hand-corrected (reshaped) stages. A bed/wake-time edit never moves startTs,
     * so the edited block lands exactly on its detected twin. Returns the aggregate plus whether an edit
     * actually applied (so the caller only overrides the day when it did), or null when nothing decodes.
     *
     * Faithful twin of Swift `dailyAggregateHonoringEdits`: substitute an edited block's stages ONLY
     * when the edit has usable (non-null) stages — an edit that reshaped to null must fall back to the
     * detected stages, never DROP the block (which would collapse the night's sleep total). `editApplied`
     * likewise reflects a real substitution. Pure: unit-tested with synthetic data, no store/stager.
     */
    fun dailyAggregateHonoringEdits(
        detected: List<Pair<Long, String?>>,
        edited: Map<Long, String?>,
    ): HonoredAggregate? {
        var applied = false
        val effective = detected.map { (startTs, detectedStages) ->
            // `edited[startTs]` is null both when the key is ABSENT and when it maps to NULL stages
            // (an edit that reshaped to nothing) — in both cases we fall back to the detected stages
            // and do NOT mark `applied`. Only a present, non-null edit substitutes, mirroring Swift's
            // `edited[d.startTs] ?? nil` requiring a non-nil value.
            val editStages = edited[startTs]
            if (editStages != null) {
                applied = true
                editStages
            } else {
                detectedStages
            }
        }
        val agg = dailyAggregate(effective) ?: return null
        return HonoredAggregate(agg, applied)
    }
}
