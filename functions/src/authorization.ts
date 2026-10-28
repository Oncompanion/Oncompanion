// Co-authored-by: ChatGPT <noreply@openai.com>
import { createHmac } from "node:crypto";
import { SearchError } from "./contracts.js";

/** Verifies both a signed-in user and an approved Firebase Android app. */
export async function authorizeTokens(
  bearer: string | undefined,
  token: string | undefined,
  allowedAppIds: string[],
  verifyIdToken: (token: string) => Promise<{ uid: string }>,
  verifyAppCheck: (token: string) => Promise<{ appId: string }>,
): Promise<string> {
  if (!bearer?.startsWith("Bearer ") || bearer.length > 8192)
    throw new SearchError(401, "unauthorized");
  if (!token || token.length > 8192)
    throw new SearchError(403, "app_check_required");
  let uid: string;
  try {
    uid = (await verifyIdToken(bearer.slice(7))).uid;
    if (!uid) throw new Error("Missing identity");
  } catch {
    throw new SearchError(401, "unauthorized");
  }
  try {
    const verified = await verifyAppCheck(token);
    if (!allowedAppIds.includes(verified.appId))
      throw new Error("Unapproved app");
  } catch {
    throw new SearchError(403, "app_check_required");
  }
  return uid;
}

/** Derives a pseudonymous quota key with a stable server-only secret, never a raw UID. */
export function quotaKeyForUid(uid: string, secret: string): string {
  if (!uid || secret.length < 32)
    throw new Error("Quota identity not configured");
  return createHmac("sha256", secret)
    .update(`hospital-quota-v1:${uid}`)
    .digest("hex");
}
