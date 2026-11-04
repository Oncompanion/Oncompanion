// Co-authored-by: ChatGPT <noreply@openai.com>
import { write } from "firebase-functions/logger";
import { SearchError } from "./contracts.js";

/** Bounded event vocabulary: no exception messages or request-derived labels. */
export type FailureEvent =
  | "hospital_search_storage_failure"
  | "hospital_search_release_failed"
  | "hospital_search_upstream_failure"
  | "hospital_search_upstream_wait_exceeds_limit"
  | "hospital_search_unexpected_failure"
  | "hospital_search_unavailable"
  | "hospital_search_capacity_exhausted";

// Only project-defined public codes are safe; SearchError currently accepts arbitrary strings.
const searchCodes = new Set([
  "invalid_query",
  "invalid_parameters",
  "request_too_large",
  "request_body_not_allowed",
  "method_not_allowed",
  "unauthorized",
  "app_check_required",
  "quota_exceeded",
  "capacity_exhausted",
  "contact_not_configured",
  "upstream_rate_limited",
  "upstream_unavailable",
  "invalid_upstream_response",
  "upstream_timeout",
  "upstream_failure",
  "storage_unavailable",
  "service_unavailable",
]);

/** Logging boundary shared by the service and HTTP adapter; payloads contain no error objects. */
export type FailureLogger = (
  event: FailureEvent,
  code?: string | number,
) => void;

function isStorageCode(code: unknown): code is number {
  return (
    typeof code === "number" &&
    Number.isInteger(code) &&
    code >= 1 &&
    code <= 16
  );
}

/** Extracts known public codes, or numeric gRPC error statuses only at storage-failure sites. */
export function failureCode(
  error: unknown,
  source: "application" | "storage" = "application",
): string | number | undefined {
  if (typeof error !== "object" || error === null) return undefined;
  // Read only a data property: diagnostic extraction must never invoke arbitrary error getters.
  const code: unknown = Object.getOwnPropertyDescriptor(error, "code")?.value;
  if (error instanceof SearchError)
    return typeof code === "string" && searchCodes.has(code) ? code : undefined;
  return source === "storage" && isStorageCode(code) ? code : undefined;
}

/** Emits bounded diagnostics without messages, stacks, metadata or arbitrary code values. */
export function logFailure(event: FailureEvent, code?: string | number): void {
  const safeCode =
    (typeof code === "string" && searchCodes.has(code)) || isStorageCode(code);
  write({
    severity:
      event === "hospital_search_capacity_exhausted" ? "WARNING" : "ERROR",
    message: event,
    event,
    ...(safeCode ? { code } : {}),
  });
}
