// Co-authored-by: ChatGPT <noreply@openai.com>
import type { Request, Response } from "firebase-functions/v2/https";
import { SearchError } from "./contracts.js";
import { HospitalSearchService } from "./service.js";
import { queryFromUrl } from "./validation.js";

/** Builds an HTTP adapter; credentials never enter search logic or storage. */
export function createHandler(
  service: HospitalSearchService,
  authorize: (request: Request) => Promise<string>,
) {
  let active = false;
  return async (request: Request, response: Response): Promise<void> => {
    response.set("Cache-Control", "no-store");
    response.set("X-Content-Type-Options", "nosniff");
    try {
      if (request.method !== "GET") {
        response.set("Allow", "GET");
        throw new SearchError(405, "method_not_allowed");
      }
      if (
        (request.rawBody?.length ?? 0) > 0 ||
        request.headers["transfer-encoding"] ||
        (request.headers["content-length"] &&
          request.headers["content-length"] !== "0")
      )
        throw new SearchError(413, "request_body_not_allowed");
      const query = queryFromUrl(request.originalUrl);
      if (active) throw new SearchError(429, "capacity_exhausted");
      active = true;
      try {
        const quotaKey = await authorize(request);
        response.status(200).json(await service.search(query, quotaKey));
      } finally {
        active = false;
      }
    } catch (error) {
      const safe =
        error instanceof SearchError
          ? error
          : new SearchError(503, "service_unavailable");
      if (safe.status === 429 || safe.status === 503)
        response.set(
          "Retry-After",
          String(Math.max(60, safe.retryAfterSeconds ?? 60)),
        );
      response.status(safe.status).json({ error: { code: safe.code } });
    }
  };
}
