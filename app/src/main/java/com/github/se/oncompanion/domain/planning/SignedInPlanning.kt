// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/**
 * The Planning entries [entriesOf] gives for the signed-in patient, following account changes.
 * Every source of the patient's own data (appointments, confirmed events) goes through it, so none
 * of them can show the previous patient's entries:
 * - signed out, it shows no entries;
 * - on an account change, it clears the previous patient's entries before reading the next one's;
 * - a read failure reaches the caller, after that clear.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeSignedInPatient(
    auth: AuthRepository,
    entriesOf: (uid: String) -> Flow<List<PlanningItem>>,
): Flow<List<PlanningItem>> =
    auth
        .observeCurrentUser()
        .map { it?.uid }
        .distinctUntilChanged()
        .transformLatest { uid ->
          // Clear old entries before collecting a different patient's cache, including on retry.
          emit(Result.success(emptyList<PlanningItem>()))
          if (uid != null) {
            emitAll(
                entriesOf(uid)
                    .map { Result.success(it) }
                    .catch { error ->
                      if (error is CancellationException) throw error
                      // Send the failure as a value so the clear reaches the UI before it is
                      // thrown. Thrown here, it would cancel the collector and get past its catch
                      // (e.g. OptionalPlanningSource's).
                      emit(Result.failure(error))
                    }
            )
          }
        }
        // Do not queue snapshots from a patient whose observation has been cancelled.
        .buffer(0)
        .map { it.getOrThrow() }
