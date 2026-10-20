// Co-authored-by: ChatGPT <noreply@openai.com>
import { request } from "node:https";
import { Readable } from "node:stream";

/** One wire attempt; the caller owns cancellation and response-size limits. */
export type HospitalTransport = (
  url: URL,
  options: { headers: Record<string, string>; signal: AbortSignal },
) => Promise<Response>;

/** Uses core HTTP without fetch's implicit 421 retry, redirects or socket reuse. */
export function requestOnce(
  url: URL,
  options: Parameters<HospitalTransport>[1],
  requester: typeof request = request,
): Promise<Response> {
  return new Promise((resolve, reject) => {
    const outgoing = requester(
      url,
      { agent: false, headers: options.headers, signal: options.signal },
      (incoming) => {
        incoming.once("error", reject);
        try {
          const status = incoming.statusCode ?? 502;
          const noBody = [204, 205, 304].includes(status);
          if (noBody) incoming.resume();
          resolve(
            new Response(
              noBody
                ? null
                : (Readable.toWeb(incoming) as ReadableStream<Uint8Array>),
              {
                status,
                headers: {
                  "Retry-After": String(incoming.headers["retry-after"] ?? ""),
                },
              },
            ),
          );
        } catch (error) {
          incoming.destroy();
          reject(error);
        }
      },
    );
    outgoing.once("error", reject);
    outgoing.end();
  });
}
