package com.github.se.oncompanion.model.cancer

import java.text.Normalizer

/**
 * Cancer types suggested while the user types their cancer type during onboarding. The user can
 * also keep their own text, so this list only needs the common cases.
 */
object CancerTypes {

  /** Common cancer types, in plain language. First version, to be checked with the Ligue. */
  val ALL: List<String> =
      listOf(
          "Bladder cancer",
          "Bone cancer",
          "Brain tumor",
          "Breast cancer",
          "Cervical cancer",
          "Colorectal cancer",
          "Endometrial (uterine) cancer",
          "Esophageal cancer",
          "Gallbladder cancer",
          "Head and neck cancer",
          "Kidney cancer",
          "Laryngeal cancer",
          "Leukemia",
          "Leukemia – acute lymphoblastic (ALL)",
          "Leukemia – acute myeloid (AML)",
          "Leukemia – chronic lymphocytic (CLL)",
          "Leukemia – chronic myeloid (CML)",
          "Liver cancer",
          "Lung cancer",
          "Lymphoma – Hodgkin",
          "Lymphoma – non-Hodgkin",
          "Melanoma",
          "Mesothelioma",
          "Multiple myeloma",
          "Myelodysplastic syndrome (MDS)",
          "Neuroendocrine tumor",
          "Oral cancer",
          "Ovarian cancer",
          "Pancreatic cancer",
          "Prostate cancer",
          "Skin cancer (non-melanoma)",
          "Soft tissue sarcoma",
          "Stomach cancer",
          "Testicular cancer",
          "Thyroid cancer",
      )

  /** Queries shorter than this give no suggestions, so the list doesn't open after one letter. */
  const val MIN_QUERY_LENGTH = 2

  /**
   * Up to [limit] entries of [catalog] matching [query], ignoring case and accents. An entry
   * matches if the query appears at the start of the entry or at the start of one of its words
   * ("myel" matches "Leukemia – acute myeloid (AML)"). Entries starting with the query come first,
   * then the others, each group in alphabetical order. An entry equal to the query is not suggested
   * (it is already typed).
   */
  fun suggest(query: String, catalog: List<String> = ALL, limit: Int = 5): List<String> {
    val needle = fold(query.trim())
    if (needle.length < MIN_QUERY_LENGTH) return emptyList()
    return catalog
        .mapNotNull { entry ->
          val folded = fold(entry)
          val start = matchStart(folded, needle)
          if (start == null || folded == needle) null else Triple(entry, folded, start)
        }
        .sortedWith(compareBy({ if (it.third == 0) 0 else 1 }, { it.second }))
        .take(limit)
        .map { it.first }
  }

  /**
   * The characters of [entry] matched by [query] (see [suggest]), e.g. to show them in bold, or
   * `null` if it doesn't match.
   */
  fun matchRange(entry: String, query: String): IntRange? {
    val needle = fold(query.trim())
    if (needle.isEmpty()) return null
    val start = matchStart(fold(entry), needle) ?: return null
    return start until start + needle.length
  }

  /** First word start in [text] where [needle] begins, or `null`. */
  private fun matchStart(text: String, needle: String): Int? =
      text.indices.firstOrNull { i ->
        (i == 0 || !text[i - 1].isLetterOrDigit()) && text.startsWith(needle, i)
      }

  /**
   * Lowercase without accents, character by character so indices stay aligned with the original
   * text ("é" → "e").
   */
  private fun fold(text: String): String =
      text
          .map { c -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar() }
          .joinToString("")
}
