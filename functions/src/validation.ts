// Co-authored-by: ChatGPT <noreply@openai.com>
import { SearchError } from "./contracts.js";

/** Accepts only the Android repository's bounded hospital-search contract. */
export function queryFromUrl(originalUrl: string): string {
  if (Buffer.byteLength(originalUrl) > 2048)
    throw new SearchError(413, "request_too_large");
  if (/%(?![0-9a-f]{2})/i.test(originalUrl))
    throw new SearchError(400, "invalid_query");
  const params = new URL(originalUrl, "https://local.invalid").searchParams;
  for (const key of params.keys()) {
    if (
      !["q", "format", "limit"].includes(key) ||
      params.getAll(key).length !== 1
    )
      throw new SearchError(400, "invalid_parameters");
  }
  if (params.get("format") !== "jsonv2" || params.get("limit") !== "10")
    throw new SearchError(400, "invalid_parameters");
  const raw = params.get("q");
  if (!raw || raw.length > 211 || /[\p{Cc}\p{Cf}\p{Cs}\p{Co}\p{Cn}]/u.test(raw))
    throw new SearchError(400, "invalid_query");
  const text = raw
    .normalize("NFKC")
    .trim()
    .replace(/\s+/gu, " ")
    .replace(/ \[hospital\]$/i, "");
  // Exclude special search syntax and URL/email forwarding; retain public place-name punctuation.
  if (
    text.length < 2 ||
    text.length > 200 ||
    !/[\p{L}\p{N}]/u.test(text) ||
    !/^[\p{L}\p{M}\p{N} .,‘’'&()\-]+$/u.test(text)
  )
    throw new SearchError(400, "invalid_query");
  return text.toLowerCase();
}
