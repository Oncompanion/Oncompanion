package com.github.se.oncompanion.model.cancer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CancerTypesTest {

  private val aml = "Leukemia – acute myeloid (AML)"

  // ---------- ALL sanity ----------

  @Test
  fun all_hasAtLeastThirtyEntries() {
    assertTrue("ALL has ${CancerTypes.ALL.size} entries", CancerTypes.ALL.size >= 30)
  }

  @Test
  fun all_hasNoBlankEntries() {
    CancerTypes.ALL.forEach { assertTrue("Blank entry: '$it'", it.isNotBlank()) }
  }

  @Test
  fun all_entriesAreTrimmed() {
    CancerTypes.ALL.forEach { assertEquals(it.trim(), it) }
  }

  @Test
  fun all_hasNoDuplicatesIgnoringCase() {
    val lower = CancerTypes.ALL.map { it.lowercase() }
    assertEquals(lower.size, lower.toSet().size)
  }

  @Test
  fun all_containsExpectedEntries() {
    listOf(
            "Breast cancer",
            "Leukemia",
            aml,
            "Lymphoma – Hodgkin",
            "Lymphoma – non-Hodgkin",
            "Brain tumor",
            "Neuroendocrine tumor",
            "Multiple myeloma",
            "Myelodysplastic syndrome (MDS)",
            "Melanoma",
            "Skin cancer (non-melanoma)",
            "Endometrial (uterine) cancer",
            "Soft tissue sarcoma",
            "Lung cancer",
            "Prostate cancer",
        )
        .forEach { assertTrue("Missing '$it'", CancerTypes.ALL.contains(it)) }
  }

  @Test
  fun minQueryLength_isTwo() {
    assertEquals(2, CancerTypes.MIN_QUERY_LENGTH)
  }

  // ---------- suggest with the default catalog ----------

  @Test
  fun suggest_breast_returnsBreastCancerFirst() {
    val result = CancerTypes.suggest("breast")
    assertEquals("Breast cancer", result.first())
  }

  @Test
  fun suggest_leuk_returnsLeukemiasOnly() {
    val result = CancerTypes.suggest("leuk", limit = 100)
    assertTrue(result.contains("Leukemia"))
    assertTrue(result.contains(aml))
    result.forEach { assertTrue(it.lowercase().startsWith("leuk")) }
    // "Leukemia" sorts before "Leukemia – ..." alphabetically.
    assertTrue(result.indexOf("Leukemia") < result.indexOf(aml))
  }

  @Test
  fun suggest_leuk_isCaseAndAccentInsensitive() {
    val expected = CancerTypes.suggest("leuk")
    assertEquals(expected, CancerTypes.suggest("LEUK"))
    assertEquals(expected, CancerTypes.suggest("Leuk"))
    assertEquals(expected, CancerTypes.suggest("léuk"))
    assertEquals(expected, CancerTypes.suggest("LÉUK"))
  }

  @Test
  fun suggest_myel_matchesWordStartsAndPrefixFirst() {
    val result = CancerTypes.suggest("myel", limit = 100)
    assertTrue(result.contains(aml))
    assertTrue(result.contains("Multiple myeloma"))
    assertTrue(result.contains("Myelodysplastic syndrome (MDS)"))
    // The prefix match comes before the later-word matches.
    assertTrue(result.indexOf("Myelodysplastic syndrome (MDS)") < result.indexOf(aml))
    assertTrue(result.first().startsWith("Myel"))
    // Later-word matches in alphabetical order.
    assertTrue(result.indexOf(aml) < result.indexOf("Multiple myeloma"))
  }

  @Test
  fun suggest_hodg_matchesBothLymphomas() {
    val result = CancerTypes.suggest("hodg", limit = 100)
    assertTrue(result.contains("Lymphoma – Hodgkin"))
    assertTrue(result.contains("Lymphoma – non-Hodgkin"))
    assertEquals(
        listOf("Lymphoma – Hodgkin", "Lymphoma – non-Hodgkin"),
        result.filter { it.startsWith("Lymphoma") },
    )
  }

  @Test
  fun suggest_tum_matchesTumorEntriesAsWordStart() {
    val result = CancerTypes.suggest("tum", limit = 100)
    assertTrue(result.contains("Brain tumor"))
    assertTrue(result.contains("Neuroendocrine tumor"))
    assertTrue(result.indexOf("Brain tumor") < result.indexOf("Neuroendocrine tumor"))
  }

  @Test
  fun suggest_aml_matchesInsideParentheses() {
    assertTrue(CancerTypes.suggest("aml").contains(aml))
  }

  @Test
  fun suggest_multiWordQuery_matches() {
    assertEquals(listOf(aml), CancerTypes.suggest("acute m"))
  }

  @Test
  fun suggest_midWordQuery_doesNotMatch() {
    val result = CancerTypes.suggest("ancer")
    assertTrue("Got $result", result.isEmpty())
  }

  @Test
  fun suggest_noMatch_returnsEmpty() {
    assertTrue(CancerTypes.suggest("zzzz").isEmpty())
  }

  @Test
  fun suggest_defaultLimitIsFive() {
    val catalog = (1..10).map { "Item $it" }
    assertEquals(5, CancerTypes.suggest("item", catalog).size)
    // "cancer" matches many entries of the default catalog.
    assertTrue(CancerTypes.suggest("cancer").size <= 5)
    assertEquals(5, CancerTypes.suggest("cancer").size)
  }

  @Test
  fun suggest_exactEntryNotSuggested_defaultCatalog() {
    val result = CancerTypes.suggest("Leukemia")
    assertFalse(result.contains("Leukemia"))
    assertTrue(result.contains(aml))
  }

  // ---------- suggest: query length and trimming ----------

  @Test
  fun suggest_emptyOrBlankQuery_returnsEmpty() {
    assertTrue(CancerTypes.suggest("").isEmpty())
    assertTrue(CancerTypes.suggest("   ").isEmpty())
  }

  @Test
  fun suggest_oneCharQuery_returnsEmpty() {
    assertTrue(CancerTypes.suggest("l").isEmpty())
    assertTrue(CancerTypes.suggest("  l  ").isEmpty())
    assertTrue(CancerTypes.suggest("a", listOf("a", "ab", "abc")).isEmpty())
  }

  @Test
  fun suggest_twoCharQuery_returnsResults() {
    assertEquals(listOf("abc"), CancerTypes.suggest("ab", listOf("abc", "xyz")))
  }

  @Test
  fun suggest_queryIsTrimmed() {
    val catalog = listOf("Leukemia", "Lung cancer", "Melanoma")
    assertEquals(listOf("Leukemia"), CancerTypes.suggest("  leu  ", catalog))
    assertEquals(listOf("Lung cancer"), CancerTypes.suggest("\tlu\n", catalog))
  }

  @Test
  fun suggest_queryTrimmedBelowMinLength_returnsEmpty() {
    assertTrue(CancerTypes.suggest(" l\t", listOf("Leukemia", "Lung cancer")).isEmpty())
  }

  // ---------- suggest: custom catalogs ----------

  @Test
  fun suggest_ordering_prefixGroupFirstThenWordGroup_eachAlphabetical() {
    val catalog = listOf("Zeta alpha", "alpha beta", "Beta alpha", "Alpha zulu", "Gamma")
    assertEquals(
        listOf("alpha beta", "Alpha zulu", "Beta alpha", "Zeta alpha"),
        CancerTypes.suggest("al", catalog, limit = 10),
    )
  }

  @Test
  fun suggest_ordering_isIndependentOfCatalogOrder() {
    val catalog = listOf("Zeta alpha", "alpha beta", "Beta alpha", "Alpha zulu")
    assertEquals(
        CancerTypes.suggest("al", catalog, limit = 10),
        CancerTypes.suggest("al", catalog.reversed(), limit = 10),
    )
  }

  @Test
  fun suggest_ordering_alphabeticalIsAccentInsensitive() {
    val catalog = listOf("Fa xy", "Ézé xy", "Eau xy")
    assertEquals(listOf("Eau xy", "Ézé xy", "Fa xy"), CancerTypes.suggest("xy", catalog))
  }

  @Test
  fun suggest_ordering_alphabeticalIsCaseInsensitive() {
    val catalog = listOf("bb xy", "AA xy", "Cc xy")
    assertEquals(listOf("AA xy", "bb xy", "Cc xy"), CancerTypes.suggest("xy", catalog))
  }

  @Test
  fun suggest_prefixMatchBeatsAlphabeticallyEarlierWordMatch() {
    val catalog = listOf("Aaa zorro", "Zorro")
    assertEquals(listOf("Zorro", "Aaa zorro"), CancerTypes.suggest("zo", catalog))
  }

  @Test
  fun suggest_accentsInCatalog_areIgnored() {
    val catalog = listOf("Séminome", "Mélanome", "Carcinome épidermoïde")
    assertEquals(listOf("Séminome"), CancerTypes.suggest("semi", catalog))
    assertEquals(listOf("Mélanome"), CancerTypes.suggest("MELA", catalog))
    assertEquals(listOf("Carcinome épidermoïde"), CancerTypes.suggest("epidermoide", catalog))
  }

  @Test
  fun suggest_accentsInQuery_areIgnored() {
    val catalog = listOf("Seminome", "Melanome")
    assertEquals(listOf("Seminome"), CancerTypes.suggest("sémi", catalog))
    assertEquals(listOf("Melanome"), CancerTypes.suggest("MÈLA", catalog))
    assertEquals(listOf("Melanome"), CancerTypes.suggest("mêla", catalog))
  }

  @Test
  fun suggest_separators_startNewWords() {
    val catalog =
        listOf(
            "Alpha–beta",
            "Alpha(beta)",
            "Alpha-beta",
            "Alpha/beta",
            "Alpha beta",
            "Alpha,beta",
            "Alphabeta",
            "Alpha1beta",
        )
    val result = CancerTypes.suggest("beta", catalog, limit = 20)
    assertEquals(
        setOf("Alpha–beta", "Alpha(beta)", "Alpha-beta", "Alpha/beta", "Alpha beta", "Alpha,beta"),
        result.toSet(),
    )
    assertEquals(6, result.size)
  }

  @Test
  fun suggest_midWordMatch_customCatalog_notSuggested() {
    assertTrue(CancerTypes.suggest("ancer", listOf("Breast cancer", "Cancer")).isEmpty())
  }

  @Test
  fun suggest_digitsCanStartWords() {
    assertEquals(listOf("Stage 2b tumor"), CancerTypes.suggest("2b", listOf("Stage 2b tumor")))
  }

  @Test
  fun suggest_multiWordQuery_customCatalog() {
    val catalog = listOf("Acute lymphoid", "Leukemia – acute myeloid (AML)", "Acute myeloid")
    assertEquals(
        listOf("Acute myeloid", "Leukemia – acute myeloid (AML)"),
        CancerTypes.suggest("acute m", catalog),
    )
  }

  @Test
  fun suggest_limitIsRespected() {
    val catalog = (1..9).map { "Item $it" }
    assertEquals(listOf("Item 1", "Item 2", "Item 3"), CancerTypes.suggest("item", catalog, 3))
    assertEquals(9, CancerTypes.suggest("item", catalog, limit = 20).size)
    assertTrue(CancerTypes.suggest("item", catalog, limit = 0).isEmpty())
  }

  @Test
  fun suggest_limitKeepsPrefixMatchesFirst() {
    val catalog = listOf("A xy", "B xy", "Xy z", "Xyz")
    assertEquals(listOf("Xy z", "Xyz"), CancerTypes.suggest("xy", catalog, limit = 2))
  }

  @Test
  fun suggest_exactMatchExcluded_caseAndAccentInsensitive() {
    val catalog = listOf("Mélanome", "Mélanome oculaire")
    assertEquals(listOf("Mélanome oculaire"), CancerTypes.suggest("melanome", catalog))
    assertEquals(listOf("Mélanome oculaire"), CancerTypes.suggest("MÉLANOME", catalog))
    assertEquals(listOf("Mélanome oculaire"), CancerTypes.suggest("  Mélanome  ", catalog))
    assertTrue(CancerTypes.suggest("mélanome oculaire", catalog).isEmpty())
  }

  @Test
  fun suggest_emptyCatalog_returnsEmpty() {
    assertTrue(CancerTypes.suggest("leuk", emptyList()).isEmpty())
  }

  // ---------- matchRange ----------

  @Test
  fun matchRange_prefix() {
    assertEquals(0..3, CancerTypes.matchRange("Leukemia", "leuk"))
    assertEquals(0..3, CancerTypes.matchRange("Leukemia", "LEUK"))
  }

  @Test
  fun matchRange_laterWord() {
    val start = aml.indexOf("myel")
    assertEquals(start..start + 3, CancerTypes.matchRange(aml, "myel"))
    assertEquals("myel", aml.substring(CancerTypes.matchRange(aml, "myel")!!))
  }

  @Test
  fun matchRange_insideParentheses() {
    val range = CancerTypes.matchRange(aml, "aml")!!
    assertEquals("AML", aml.substring(range))
  }

  @Test
  fun matchRange_afterHyphen() {
    val entry = "Lymphoma – non-Hodgkin"
    assertEquals("Hodg", entry.substring(CancerTypes.matchRange(entry, "hodg")!!))
  }

  @Test
  fun matchRange_multiWordQuery() {
    assertEquals("acute m", aml.substring(CancerTypes.matchRange(aml, "acute m")!!))
  }

  @Test
  fun matchRange_firstMatchingWordStartIsUsed() {
    assertEquals(6..7, CancerTypes.matchRange("alpha beta beta", "be"))
    assertEquals(0..1, CancerTypes.matchRange("beta beta", "be"))
  }

  @Test
  fun matchRange_queryIsTrimmed() {
    assertEquals(0..3, CancerTypes.matchRange("Leukemia", "  leuk  "))
  }

  @Test
  fun matchRange_oneCharQueryCanMatch() {
    assertEquals(0..0, CancerTypes.matchRange("Leukemia", "l"))
    assertEquals(6..6, CancerTypes.matchRange("Brain tumor", "t"))
  }

  @Test
  fun matchRange_noMatch_returnsNull() {
    assertNull(CancerTypes.matchRange("Leukemia", "xyz"))
    assertNull(CancerTypes.matchRange("Breast cancer", "ancer"))
    assertNull(CancerTypes.matchRange("Leuk", "leukemia"))
  }

  @Test
  fun matchRange_emptyOrBlankQuery_returnsNull() {
    assertNull(CancerTypes.matchRange("Leukemia", ""))
    assertNull(CancerTypes.matchRange("Leukemia", "   "))
  }

  @Test
  fun matchRange_accents_indicesReferToOriginalEntry() {
    assertEquals(0..3, CancerTypes.matchRange("Séminome", "semi"))
    assertEquals(0..3, CancerTypes.matchRange("Seminome", "sémi"))
    assertEquals(3..6, CancerTypes.matchRange("Le mélanome", "MÉLA"))
    val entry = "Carcinome épidermoïde"
    assertEquals("épidermoïde", entry.substring(CancerTypes.matchRange(entry, "epidermoide")!!))
  }

  @Test
  fun matchRange_wholeEntry() {
    assertEquals(0..7, CancerTypes.matchRange("Melanoma", "melanoma"))
  }
}
