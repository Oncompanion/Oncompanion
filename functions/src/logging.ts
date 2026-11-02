// Co-authored-by: ChatGPT <noreply@openai.com>
import { write } from "firebase-functions/logger";

/** Bounded event vocabulary: no exception messages or request-derived labels. */
export type FailureEvent =
  | "hospital_search_storage_failure"
  | "hospital_search_release_failed"
  | "hospital_search_upstream_failure"
  | "hospital_search_upstream_wait_exceeds_limit"
  | "hospital_search_unexpected_failure"
  | "hospital_search_unavailable"
  | "hospital_search_capacity_exhausted";

/** Emits structured operational failures without accepting sensitive payloads. */
export function logFailure(event: FailureEvent): void {
  write({ severity: "ERROR", message: event, event });
}
