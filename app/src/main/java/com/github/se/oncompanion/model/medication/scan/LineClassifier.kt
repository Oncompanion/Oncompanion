// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import com.github.se.oncompanion.model.medication.scan.PrescriptionLexicon.Label

/** What a line of a prescription is, which decides what the parser takes from it. */
internal enum class LineKind {
  /** Says whose lines follow, e.g. "PATIENT" or "Médecin traitant". */
  LABEL,
  /** Opens the list of medications: "Rp/", "Rx", "Ordonnance". */
  LIST_START,
  /** A column heading of a table of medications, e.g. "Posologie". */
  TABLE_HEADING,
  /** The doctor who wrote the prescription. */
  DOCTOR,
  /** Another doctor named on the page, e.g. the family doctor: never the prescriber. */
  OTHER_DOCTOR,
  /** About the patient: name, birth date, insurance. Its dates are never the prescription's. */
  PATIENT,
  /** Holds the prescription's date. */
  DATE,
  /** Starts a medication: a name with its strength, maybe followed by how to take it. */
  MEDICATION,
  /** How to take the medication above: how much, how often, for how long. */
  DOSING,
  /** Contact details, identifiers, scraps of other sheets at the photo's edge: ignored. */
  NOISE,
  /** Anything else, e.g. a clinic's name or a note to the patient. */
  OTHER,
}

/**
 * A line read on the prescription, cleaned up, and what it is.
 *
 * @property text the line with ML Kit's predictable mistakes undone (see [cleanedOcr])
 */
internal data class ClassifiedLine(val line: RecognizedLine, val text: String, val kind: LineKind)

/** A row of the page (see [TextRow]) with what each of its lines is. */
internal data class ClassifiedRow(val lines: List<ClassifiedLine>)

/**
 * The rows of [text] in reading order, each line cleaned up and classified. A label ("PATIENT",
 * "Médecin traitant") applies to the lines just below it in its column, so a family doctor isn't
 * taken for the prescriber and the patient's birth date isn't taken for the prescription's.
 */
internal fun classify(text: RecognizedText): List<ClassifiedRow> {
  val labels = mutableListOf<LabelledBlock>()
  return readingOrder(text).map { row ->
    ClassifiedRow(
        row.lines.map { line ->
          val cleaned = cleanedOcr(line.text)
          val words = comparable(cleaned)
          val label = PrescriptionLexicon.labelOf(words)
          if (label != null && line.box != null) labels += LabelledBlock(label, line.box)
          val kind =
              if (label != null) LineKind.LABEL
              else kindOf(line, words, labelAbove(labels, line), text.width, text.height)
          ClassifiedLine(line, cleaned, kind)
        }
    )
  }
}

/** What [line] is, from its [words] in comparable form and the [label] above it, if any. */
private fun kindOf(line: RecognizedLine, words: String, label: Label?, width: Int, height: Int) =
    with(PrescriptionLexicon) {
      when {
        isEdgeScrap(line, width, height) -> LineKind.NOISE
        isListStart(words) -> LineKind.LIST_START
        isTableHeading(words) -> LineKind.TABLE_HEADING
        label == Label.PATIENT || hasPatientClue(words) || hasBirthClue(words) -> LineKind.PATIENT
        startsWithFamilyDoctor(words) || (label == Label.FAMILY_DOCTOR && hasDoctorTitle(words)) ->
            LineKind.OTHER_DOCTOR
        hasDoctorTitle(words) -> LineKind.DOCTOR
        // After the doctor and the patient, whose lines often end with an address
        isContactOrIdentifier(words) -> LineKind.NOISE
        hasStrength(words) && NAME.containsMatchIn(words) -> LineKind.MEDICATION
        datesIn(words).isNotEmpty() -> LineKind.DATE
        hasDoseQuantity(words) || hasFrequency(words) || hasDuration(words) -> LineKind.DOSING
        words.length <= 2 || words.none { it.isLetter() } -> LineKind.NOISE
        else -> LineKind.OTHER
      }
    }

/**
 * Whether [line] is a scrap of another sheet in the background: unsure, and touching the photo's
 * edge, where the photo cut it.
 */
private fun isEdgeScrap(line: RecognizedLine, width: Int, height: Int): Boolean {
  val box = line.box ?: return false
  if (line.confidence >= EDGE_SCRAP_CONFIDENCE) return false
  val marginX = width * EDGE_MARGIN
  val marginY = height * EDGE_MARGIN
  return box.left <= marginX ||
      box.top <= marginY ||
      box.right >= width - marginX ||
      box.bottom >= height - marginY
}

/** A label and where it is, so the lines below it in its column know whose they are. */
private class LabelledBlock(val label: Label, box: TextBox) {
  val left = box.left
  val right = box.right
  val lineHeight = box.bottom - box.top
  var bottom = box.bottom
}

/**
 * The label [line] is under: the last one whose column overlaps it, if the line comes less than a
 * few lines below the block's previous line. The block then extends down to [line].
 */
private fun labelAbove(labels: List<LabelledBlock>, line: RecognizedLine): Label? {
  val box = line.box ?: return null
  val block =
      labels.lastOrNull {
        box.left < it.right &&
            box.right > it.left &&
            box.top >= it.bottom - it.lineHeight &&
            box.top - it.bottom <= it.lineHeight * LABEL_REACH
      } ?: return null
  block.bottom = maxOf(block.bottom, box.bottom)
  return block.label
}

/** A word of at least three letters: a medication's name next to its strength. */
private val NAME = Regex("""[a-z]{3,}""")

/** Lines this sure are kept even at the photo's edge (printed text read 0.83 to 0.92). */
private const val EDGE_SCRAP_CONFIDENCE = 0.85f

/** How close to the photo's edge, as a share of its size, a line counts as cut by it. */
private const val EDGE_MARGIN = 0.01f

/** How many line heights below a label's block a line still belongs to it. */
private const val LABEL_REACH = 2.5f
