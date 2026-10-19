package com.symptree.core.booking

import com.symptree.core.source.DoctorRef
import com.symptree.core.source.Slot

/**
 * Pluggable slot-selection seam (AC-W03362.2). [FirstAvailable] is the only MVP
 * strategy; `round_robin` / `least_busy` are deferred to FR-2VQ7CA.
 */
interface SlotStrategy {
    /** Pick one slot from each doctor's remaining slots, or `null` if none exist. */
    fun pick(perDoctor: Map<DoctorRef, List<Slot>>): Slot?
}

/**
 * Picks the globally earliest remaining slot across candidate doctors. Ties
 * (equal earliest start) break toward the doctor with the most free capacity
 * (most remaining slots) in the window.
 */
object FirstAvailable : SlotStrategy {
    override fun pick(perDoctor: Map<DoctorRef, List<Slot>>): Slot? {
        var best: Slot? = null
        var bestCapacity = -1
        for ((_, slots) in perDoctor) {
            if (slots.isEmpty()) continue
            val earliest = slots.minByOrNull { it.start } ?: continue
            val capacity = slots.size
            val current = best
            val isBetter = current == null ||
                earliest.start.isBefore(current.start) ||
                (earliest.start == current.start && capacity > bestCapacity)
            if (isBetter) {
                best = earliest
                bestCapacity = capacity
            }
        }
        return best
    }
}
