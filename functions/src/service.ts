// Co-authored-by: ChatGPT <noreply@openai.com>
import { createHash, randomUUID } from "node:crypto";
import { HospitalClient, SearchError, SearchStore } from "./contracts.js";

const MAX_RELEASE_ATTEMPTS = 3;

/** Coordinates shared cache and exclusive upstream access without a request queue. */
export class HospitalSearchService {
  /** Injects shared coordination and upstream boundaries for portability. */
  constructor(
    private readonly store: SearchStore,
    private readonly client: HospitalClient,
    /** Logs a fixed storage-failure event; injectable to assert logging in tests without queries, UIDs or error details. */
    private readonly logFailure: () => void = () =>
      console.error("hospital_search_storage_failure"),
  ) {}
  /**
   * Returns shared results or makes one globally authorized upstream request.
   * quotaKey is the hashed user UID; its quota is charged only on an initial cache miss.
   */
  async search(query: string, quotaKey: string) {
    const key = createHash("sha256")
      .update(`hospital-jsonv2-v1:${query}`)
      .digest("hex");
    try {
      const cached = await this.store.cached(key);
      if (cached !== undefined) return cached;
      await this.store.consumeQuota(quotaKey);
      const owner = randomUUID();
      if (!(await this.store.acquire(owner))) {
        // A completed identical request can now be cached; otherwise reject without queuing.
        const completed = await this.store.cached(key);
        if (completed !== undefined) return completed;
        throw new SearchError(429, "capacity_exhausted");
      }
      let cooldown = 1000;
      let upstreamPaused = false;
      try {
        const rechecked = await this.store.cached(key);
        if (rechecked !== undefined) return rechecked;
        const results = await this.client.search(query);
        await this.store.save(key, results);
        return results;
      } catch (error) {
        // Retain the existing fail-closed lock rather than retry before a longer upstream wait.
        if (
          error instanceof SearchError &&
          [429, 503].includes(error.status) &&
          error.upstreamWaitExceedsLimit
        ) {
          upstreamPaused = true;
          console.error("hospital_search_upstream_wait_exceeds_limit");
        }
        // Back off all users after upstream failures; failures are never cached.
        cooldown =
          error instanceof SearchError && [429, 503].includes(error.status)
            ? Math.max(60, error.retryAfterSeconds ?? 60) * 1000
            : 10_000;
        throw error;
      } finally {
        // Release after completion, except when a longer upstream wait requires operator recovery.
        for (
          let attempt = 1;
          attempt <= MAX_RELEASE_ATTEMPTS && !upstreamPaused;
          attempt++
        ) {
          try {
            await this.store.release(owner, cooldown);
            break;
          } catch (error) {
            this.logFailure();
            if (attempt === MAX_RELEASE_ATTEMPTS) throw error;
          }
        }
      }
    } catch (error) {
      if (error instanceof SearchError) throw error;
      this.logFailure();
      throw new SearchError(503, "storage_unavailable");
    }
  }
}
