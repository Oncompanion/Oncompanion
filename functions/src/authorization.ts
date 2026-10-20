// Co-authored-by: ChatGPT <noreply@openai.com>
import { SearchError } from "./contracts.js";

/** Verifies both a signed-in user and an approved Firebase Android app. */
export async function authorizeTokens(
  bearer: string | undefined,
  token: string | undefined,
  allowedAppIds: string[],
  verifyIdToken: (token: string) => Promise<unknown>,
  verifyAppCheck: (token: string) => Promise<{ appId: string }>,
): Promise<void> {
  if (!bearer?.startsWith("Bearer ") || bearer.length > 8192)
    throw new SearchError(401, "unauthorized");
  if (!token || token.length > 8192)
    throw new SearchError(403, "app_check_required");
  try {
    await verifyIdToken(bearer.slice(7));
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
}
