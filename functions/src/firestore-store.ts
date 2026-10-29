// Co-authored-by: ChatGPT <noreply@openai.com>
import { FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { Hospital, SearchError, SearchStore } from "./contracts.js";

const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;
const MAX_HOURLY_CACHE_MISSES = 10;
const MAX_DAILY_CACHE_MISSES = 50;

/** Admin-only seven-day cache, distinct from both Android caches. */
export class FirestoreSearchStore implements SearchStore {
  /** Creates an adapter for the selected server-side database. */
  constructor(private readonly db: Firestore) {}
  /** Returns fresh shared results, including empty results. */
  async cached(key: string): Promise<Hospital[] | undefined> {
    const snapshot = await this.db.doc(`hospitalSearchCache/${key}`).get();
    if (!snapshot.exists) return undefined;
    const data = snapshot.data()!;
    if (!(data.expiresAt instanceof Timestamp) || !Array.isArray(data.results))
      throw new Error("Invalid cache record");
    return data.expiresAt.toMillis() > snapshot.readTime.toMillis()
      ? data.results
      : undefined;
  }
  /** Persists a successful response with a bounded freshness period. */
  async save(key: string, results: Hospital[]): Promise<void> {
    const ref = this.db.doc(`hospitalSearchCache/${key}`);
    const snapshot = await ref.get();
    await ref.set({
      results,
      expiresAt: Timestamp.fromMillis(
        snapshot.readTime.toMillis() + 7 * DAY_MS,
      ),
    });
  }
  /** Atomically enforces the rolling hourly and daily cache-miss limits defined above. */
  async consumeQuota(key: string): Promise<void> {
    if (!/^[a-f0-9]{64}$/.test(key)) throw new Error("Invalid quota key");
    const ref = this.db.doc(`hospitalSearchQuota/${key}`);
    await this.db.runTransaction(
      async (transaction) => {
        const snapshot = await transaction.get(ref);
        const now = snapshot.readTime.toMillis();
        const stored = snapshot.exists ? snapshot.get("misses") : [];
        if (
          !Array.isArray(stored) ||
          stored.some((time) => !(time instanceof Timestamp))
        )
          throw new Error("Invalid quota state");
        // Rolling windows avoid double bursts at calendar boundaries; the array is bounded by the daily limit.
        const misses: Timestamp[] = stored.filter(
          (time) => time.toMillis() > now - DAY_MS,
        );
        if (
          misses.length >= MAX_DAILY_CACHE_MISSES ||
          misses.filter((time) => time.toMillis() > now - HOUR_MS).length >=
            MAX_HOURLY_CACHE_MISSES
        )
          throw new SearchError(429, "quota_exceeded");
        transaction.set(ref, {
          misses: [...misses, snapshot.readTime],
          expiresAt: Timestamp.fromMillis(now + DAY_MS),
        });
      },
      { maxAttempts: 10 },
    );
  }
  /** Atomically claims exclusive upstream access when the server cooldown permits. */
  async acquire(owner: string): Promise<boolean> {
    const ref = this.db.doc("hospitalSearchControl/nominatim");
    return this.db.runTransaction(
      async (transaction) => {
        const snapshot = await transaction.get(ref);
        const data = snapshot.data();
        // A held lock NEVER expires: paused or crashed owners cannot overlap a replacement.
        if (data?.owner) return false;
        if (data) {
          if (
            !(data.releasedAt instanceof Timestamp) ||
            !Number.isSafeInteger(data.cooldownMs) ||
            data.cooldownMs < 1000
          )
            throw new Error("Invalid limiter state");
          if (
            snapshot.readTime.toMillis() <
            data.releasedAt.toMillis() + data.cooldownMs
          )
            return false;
        }
        transaction.set(ref, { owner });
        return true;
      },
      { maxAttempts: 3 },
    );
  }
  /** Releases only the matching owner and starts a server-timestamp cooldown. */
  async release(owner: string, cooldownMs: number): Promise<void> {
    const ref = this.db.doc("hospitalSearchControl/nominatim");
    await this.db.runTransaction(
      async (transaction) => {
        const snapshot = await transaction.get(ref);
        if (snapshot.get("releasedOwner") === owner && !snapshot.get("owner"))
          return;
        if (snapshot.get("owner") !== owner)
          throw new Error("Lost limiter ownership");
        transaction.set(ref, {
          releasedOwner: owner,
          releasedAt: FieldValue.serverTimestamp(),
          cooldownMs,
        });
      },
      { maxAttempts: 3 },
    );
  }
}
