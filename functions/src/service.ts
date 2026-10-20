// Co-authored-by: ChatGPT <noreply@openai.com>
import { createHash, randomUUID } from "node:crypto";
import { HospitalClient, SearchError, SearchStore } from "./contracts.js";

/** Coordinates shared cache and exclusive upstream access without a request queue. */
export class HospitalSearchService {
  /** Injects shared coordination and upstream boundaries for portability. */
  constructor(
    private readonly store: SearchStore,
    private readonly client: HospitalClient,
  ) {}
  /** Returns shared results or makes one globally authorized upstream request. */
  async search(query: string) {
    const key = createHash("sha256")
      .update(`hospital-jsonv2-v1:${query}`)
      .digest("hex");
    try {
      const cached = await this.store.cached(key);
      if (cached !== undefined) return cached;
      const owner = randomUUID();
      if (!(await this.store.acquire(owner))) {
        // A completed identical request can now be cached; otherwise reject without queuing.
        const completed = await this.store.cached(key);
        if (completed !== undefined) return completed;
        throw new SearchError(429, "capacity_exhausted");
      }
      let cooldown = 1000;
      try {
        const rechecked = await this.store.cached(key);
        if (rechecked !== undefined) return rechecked;
        const results = await this.client.search(query);
        await this.store.save(key, results);
        return results;
      } catch (error) {
        // Back off all users after upstream failures; failures are never cached.
        cooldown =
          error instanceof SearchError && error.status === 429
            ? Math.max(60, error.retryAfterSeconds ?? 60) * 1000
            : 10_000;
        throw error;
      } finally {
        // Release only after upstream completion/abort and cache persistence, even on failures.
        await this.store.release(owner, cooldown);
      }
    } catch (error) {
      if (error instanceof SearchError) throw error;
      throw new SearchError(503, "storage_unavailable");
    }
  }
}
