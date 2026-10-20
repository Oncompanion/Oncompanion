// Co-authored-by: ChatGPT <noreply@openai.com>
import { Hospital, HospitalClient, SearchError } from "./contracts.js";

/** Fixed-origin HTTP client with bounded response size, no redirects and no retries. */
export class NominatimClient implements HospitalClient {
  /** Injects the deployment contact and transport for isolated tests. */
  constructor(
    private readonly contact: string,
    private readonly fetcher: typeof fetch = fetch,
  ) {}
  /** Fetches and validates hospital-only results within the upstream request budget. */
  async search(query: string): Promise<Hospital[]> {
    if (
      !/^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9.-]+\.[a-z]{2,}$/i.test(
        this.contact,
      ) ||
      this.contact.length > 254
    )
      throw new SearchError(503, "contact_not_configured");
    const url = new URL("https://nominatim.openstreetmap.org/search");
    url.search = new URLSearchParams({
      q: `${query} [hospital]`,
      format: "jsonv2",
      limit: "10",
    }).toString();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 5000);
    try {
      const response = await this.fetcher(url, {
        redirect: "error",
        signal: controller.signal,
        headers: {
          "User-Agent": `Oncompanion/1.0 (+https://github.com/Oncompanion/Oncompanion; ${this.contact})`,
          Accept: "application/json",
          "Accept-Language": "en",
        },
      });
      if (response.status === 429)
        throw new SearchError(429, "upstream_rate_limited");
      if (!response.ok) throw new SearchError(502, "upstream_unavailable");
      const reader = response.body?.getReader();
      if (!reader) throw new SearchError(502, "invalid_upstream_response");
      const chunks: Uint8Array[] = [];
      let size = 0;
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.length;
        if (size > 262144)
          throw new SearchError(502, "invalid_upstream_response");
        chunks.push(value);
      }
      const results: unknown = JSON.parse(
        Buffer.concat(chunks).toString("utf8"),
      );
      if (!Array.isArray(results) || results.length > 10)
        throw new SearchError(502, "invalid_upstream_response");
      const hospitals: Hospital[] = [];
      for (const item of results) {
        if (!item || typeof item !== "object")
          throw new SearchError(502, "invalid_upstream_response");
        if (
          item.type !== "hospital" ||
          !["amenity", "healthcare"].includes(item.category)
        )
          continue;
        if (
          !["node", "way", "relation"].includes(item.osm_type) ||
          !Number.isSafeInteger(item.osm_id) ||
          item.osm_id <= 0 ||
          typeof item.display_name !== "string" ||
          !item.display_name.trim() ||
          typeof item.lat !== "string" ||
          !item.lat.trim() ||
          !Number.isFinite(+item.lat) ||
          Math.abs(+item.lat) > 90 ||
          typeof item.lon !== "string" ||
          !item.lon.trim() ||
          !Number.isFinite(+item.lon) ||
          Math.abs(+item.lon) > 180
        )
          throw new SearchError(502, "invalid_upstream_response");
        hospitals.push({
          osm_type: item.osm_type,
          osm_id: item.osm_id,
          category: item.category,
          type: item.type,
          name:
            typeof item.name === "string" && item.name.trim()
              ? item.name
              : item.display_name.split(",")[0],
          display_name: item.display_name,
          lat: item.lat,
          lon: item.lon,
        });
      }
      return hospitals;
    } catch (error) {
      if (error instanceof SearchError) throw error;
      throw new SearchError(
        controller.signal.aborted ? 504 : 502,
        controller.signal.aborted ? "upstream_timeout" : "upstream_failure",
      );
    } finally {
      controller.abort();
      clearTimeout(timer);
    }
  }
}
