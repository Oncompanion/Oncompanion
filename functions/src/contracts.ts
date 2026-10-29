// Co-authored-by: ChatGPT <noreply@openai.com>

/** Maximum automatic cooldown and client retry hint; longer upstream waits require operator review. */
export const MAX_RETRY_AFTER_SECONDS = 180;

/** Sanitized failure safe to expose to callers. */
export class SearchError extends Error {
  readonly retryAfterSeconds?: number;
  readonly upstreamWaitExceedsLimit: boolean;

  /** Carries a public status and stable code without private exception details. */
  constructor(
    public readonly status: number,
    public readonly code: string,
    retryAfterSeconds?: number,
  ) {
    super(code);
    this.upstreamWaitExceedsLimit =
      retryAfterSeconds !== undefined &&
      retryAfterSeconds > MAX_RETRY_AFTER_SECONDS;
    this.retryAfterSeconds =
      retryAfterSeconds === undefined
        ? undefined
        : Number.isNaN(retryAfterSeconds)
          ? 60
          : Math.min(
              MAX_RETRY_AFTER_SECONDS,
              Math.max(60, Math.ceil(retryAfterSeconds)),
            );
  }
}
/** JSON v2 fields consumed by the existing Android hospital parser. */
export interface Hospital {
  osm_type: string;
  osm_id: number;
  category: string;
  type: string;
  name: string;
  display_name: string;
  lat: string;
  lon: string;
}
/** Shared storage boundary; acquisition must be atomic across all instances. */
export interface SearchStore {
  cached(key: string): Promise<Hospital[] | undefined>;
  save(key: string, results: Hospital[]): Promise<void>;
  consumeQuota(key: string): Promise<void>;
  acquire(owner: string): Promise<boolean>;
  release(owner: string, cooldownMs: number): Promise<void>;
}
/** Upstream boundary, injectable so tests never reach public Nominatim. */
export interface HospitalClient {
  search(query: string): Promise<Hospital[]>;
}
