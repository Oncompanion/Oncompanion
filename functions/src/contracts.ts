// Co-authored-by: ChatGPT <noreply@openai.com>

/** Sanitized failure safe to expose to callers. */
export class SearchError extends Error {
  /** Carries a public status and stable code without private exception details. */
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly retryAfterSeconds?: number,
  ) {
    super(code);
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
  acquire(owner: string): Promise<boolean>;
  release(owner: string, cooldownMs: number): Promise<void>;
}
/** Upstream boundary, injectable so tests never reach public Nominatim. */
export interface HospitalClient {
  search(query: string): Promise<Hospital[]>;
}
