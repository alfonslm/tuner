package io.github.alfonslm.tuner.app

import io.github.alfonslm.tuner.music.DEFAULT_TUNING_IDS
import io.github.alfonslm.tuner.music.STANDARD_TUNINGS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuningCatalogTest {
    private val standard = TuningCatalog.standard

    private fun TuningCatalog.copyOf(id: String): Pair<TuningCatalog, String> {
        val (catalog, copy) = duplicate(id)
        return catalog to assertNotNull(copy)
    }

    @Test
    fun `starts with every standard tuning listed and one per instrument in use`() {
        assertEquals(STANDARD_TUNINGS.map { it.id }, standard.tunings.map { it.id })
        assertEquals(DEFAULT_TUNING_IDS, standard.inUse.map { it.id }.toSet())
        assertTrue(standard.tunings.none { it.custom })
    }

    @Test
    fun `tunings can be switched off and on`() {
        val changed = standard.setEnabled("viola", false).setEnabled("guitar-drop-d", true)
        assertTrue("viola" !in changed.inUse.map { it.id })
        assertTrue("guitar-drop-d" in changed.inUse.map { it.id })
        assertEquals(mapOf("viola" to false, "guitar-drop-d" to true), changed.switches)
        assertEquals(standard, changed.setEnabled("viola", true).setEnabled("guitar-drop-d", false))
    }

    @Test
    fun `duplicating makes an editable copy right below the original`() {
        val (catalog, id) = standard.copyOf("guitar")
        val index = catalog.tunings.indexOfFirst { it.id == "guitar" }
        val copy = catalog.tunings[index + 1]
        assertEquals(id, copy.id)
        assertEquals("Guitar copy", copy.preset.name)
        assertEquals(standard["guitar"]!!.strings, copy.strings)
        assertEquals(standard["guitar"]!!.preset.harmonics, copy.preset.harmonics)
        assertTrue(copy.custom && copy.enabled)

        // Copies get distinct ids and names, and a copy can itself be copied.
        val (twice, second) = catalog.copyOf("guitar")
        val (thrice, third) = twice.copyOf(second)
        assertEquals(3, setOf(id, second, third).size)
        assertEquals(setOf("Guitar copy", "Guitar copy 2", "Guitar copy 2 copy"), thrice.tunings.filter { it.custom }.map { it.preset.name }.toSet())
        assertNull(standard.duplicate("no-such-tuning").second)
    }

    @Test
    fun `a custom tuning can be renamed, retuned and given more or fewer strings`() {
        val (copied, id) = standard.copyOf("guitar")
        val edited = copied
            .rename(id, "Open\tD\nminor")
            .shiftString(id, 0, -2) // E2 -> D2
            .shiftString(id, 4, -2) // B3 -> A3
            .shiftString(id, 5, -2) // E4 -> D4
            .shiftString(id, 2, 0)
            .removeString(id, 3)
            .addString(id)
        val tuning = edited[id]!!
        assertEquals("Open D minor", tuning.preset.name)
        assertEquals(listOf("D2", "A2", "D3", "A3", "D4", "G4"), tuning.strings)
        assertEquals(standard["guitar"], edited["guitar"]) // the original is untouched
    }

    @Test
    fun `edits stay within what the tuner can follow`() {
        var (catalog, id) = standard.copyOf("bass-5")
        repeat(40) { catalog = catalog.shiftString(id, 0, -1) }
        assertEquals("B0", catalog[id]!!.strings[0])
        repeat(120) { catalog = catalog.shiftString(id, 4, 1) }
        assertEquals("C7", catalog[id]!!.strings[4])

        repeat(20) { catalog = catalog.addString(id) }
        assertEquals(TuningCatalog.MAX_STRINGS, catalog[id]!!.strings.size)
        repeat(20) { catalog = catalog.removeString(id, 0) }
        assertEquals(1, catalog[id]!!.strings.size)

        assertEquals(40, catalog.rename(id, "x".repeat(100))[id]!!.preset.name.length)
        assertEquals(catalog, catalog.shiftString(id, 7, 1).removeString(id, 7))
    }

    @Test
    fun `standard tunings cannot be edited or deleted, only switched off`() {
        val untouched = standard.rename("guitar", "Mine").shiftString("guitar", 0, 1).addString("guitar").removeString("guitar", 0).delete("guitar")
        assertEquals(standard, untouched)

        val (copied, id) = standard.copyOf("ukulele")
        assertEquals(standard, copied.delete(id))
    }

    @Test
    fun `what is saved brings the same list back, in the same order`() {
        val (first, banjo) = standard.setEnabled("cello", false).setEnabled("mandolin", true).copyOf("banjo")
        val (second, guitar) = first.rename(banjo, "Banjo · Double C").shiftString(banjo, 2, 0).copyOf("guitar")
        val catalog = second.removeString(guitar, 5).removeString(guitar, 4).rename(guitar, "Cigar box").setEnabled(guitar, false)

        assertEquals(catalog, TuningCatalog.restore(catalog.switches, catalog.encodeCustom()))
        assertEquals(standard, TuningCatalog.restore(emptyMap(), ""))
    }

    @Test
    fun `damaged saved tunings are skipped, not fatal`() {
        val (catalog, id) = standard.copyOf("guitar")
        val good = catalog.encodeCustom()
        val damaged = listOf(
            "nonsense",
            "custom-9\tguitar\t1\t8\t5.0E-5\tE2,H9,D3\tBad note",
            "custom-8\tguitar\t1\teight\t5.0E-5\tE2,A2\tBad number",
            "custom-7\tguitar\t1\t8\t5.0E-5\t\tNo strings",
            good.replace(id, "guitar"), // would shadow a standard tuning
            good,
        ).joinToString("\n")
        assertEquals(catalog, TuningCatalog.restore(emptyMap(), damaged))
        // A tuning whose neighbour is gone still comes back, at the end.
        val orphan = TuningCatalog.restore(emptyMap(), good.replace("\tguitar\t", "\tgone\t"))
        assertEquals(id, orphan.tunings.last().id)
    }
}
