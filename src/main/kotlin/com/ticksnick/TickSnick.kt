package com.ticksnick

import java.nio.file.Path

/**
 * Reading what [Run.save] wrote: [load] a folder of runs and render it, or [diff] two.
 *
 * **A folder is the only thing read**, as it is the only thing written: one folder per profile,
 * every file in it one run. Choosing which runs count is done on the folder - a run not to count is
 * moved out - and not in the tool. One run renders too, with a warning saying its times have
 * no interval.
 */
object TickSnick {
    /** Every run in [folder], oldest first. */
    @JvmStatic
    fun load(folder: Path): Profile = Profile.load(folder).also { Logo.once() }

    /**
     * [before] -> [after]: each side the mean of its runs, each change judged on its 95% interval once both have two.
     * [unicode], [view], [lenses] and [filter] as in [Profile.render]: brief by default, one line a row, every op shown.
     * A row passes [filter] when either side may, or its change may: `Filter.of("change(wall) <= -5%")`.
     */
    @JvmStatic
    @JvmOverloads
    fun diff(
        before: Profile, after: Profile, unicode: Boolean = false, view: View = View.BRIEF, lenses: Set<Lens> = emptySet(),
        filter: Filter = Filter.NONE,
    ): String = withSymbols(unicode) { diffText(before, after, view, lenses, filter) }.also { Logo.once() }
}
