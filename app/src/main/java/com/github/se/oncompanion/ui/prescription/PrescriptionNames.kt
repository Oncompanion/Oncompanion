// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import com.github.se.oncompanion.model.carecircle.addedByName
import com.github.se.oncompanion.model.medication.Prescription

/**
 * This prescription with who added and last edited it as the patient named those members in their
 * circle ([names], so a rename shows), as for symptoms and appointments; the stored name once they
 * left it.
 */
internal fun Prescription.namedBy(names: Map<String, String>): Prescription =
    copy(
        addedByName = addedByName(addedBy, addedByName, names),
        editedByName = addedByName(editedBy, editedByName, names),
    )
