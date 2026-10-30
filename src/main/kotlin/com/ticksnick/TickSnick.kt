package com.ticksnick

import java.nio.file.Path

/**
 * Reading what [Run.save] wrote: [load] a folder of runs and render it, or [diff] two.
 *
 * **A folder is the only thing read**, as it is the only thing written: one folder per profile,
 * every file in it one run. Choosing which runs count is done on the folder - a run not to count is
 * moved out - and not in the tool. One run renders too, with a `CAREFUL` line saying its times have
 * no interval.
 */
object TickSnick {
    /** Every run in [folder], oldest first. */
    @JvmStatic
    fun load(folder: Path): Profile = Profile.load(folder).also { Logo.once() }

    /** [before] -> [after]: each side the mean of its runs, the change with its 95% interval once both have two. */
    @JvmStatic
    fun diff(before: Profile, after: Profile): String = ProfileDiff(before, after).render().also { Logo.once() }
}
