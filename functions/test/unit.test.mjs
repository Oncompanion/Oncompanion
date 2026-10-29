// Co-authored-by: ChatGPT <noreply@openai.com>
import assert from "node:assert/strict";
import { test } from "node:test";
import { queryFromUrl } from "../lib/validation.js";
import { HospitalSearchService } from "../lib/service.js";
import { NominatimClient } from "../lib/nominatim.js";
import { createHandler } from "../lib/handler.js";
import { SearchError } from "../lib/contracts.js";

const hospital = {
  osm_type: "way",
  osm_id: 123,
  category: "amenity",
  type: "hospital",
  name: "Hôpital",
  display_name: "Hôpital, Genève, Suisse",
  lat: "46.2",
  lon: "6.1",
};
const url = (q) =>
  `/hospitalSearch?${new URLSearchParams({ q, format: "jsonv2", limit: "10" })}`;
const failure = (status, code) => (e) =>
  e instanceof SearchError && e.status === status && (!code || e.code === code);
function fakeStore() {
  const values = new Map();
  return {
    values,
    async consumeQuota() {},
    locked: false,
    releases: [],
    async cached(key) {
      return values.get(key);
    },
    async save(key, value) {
      values.set(key, value);
    },
    async acquire() {
      if (this.locked) return false;
      this.locked = true;
      return true;
    },
    async release(owner, gap) {
      this.releases.push(gap);
      this.locked = false;
    },
  };
}
function response() {
  return {
    headers: {},
    statusCode: 0,
    set(k, v) {
      this.headers[k] = v;
      return this;
    },
    status(s) {
      this.statusCode = s;
      return this;
    },
    json(body) {
      this.body = body;
      return this;
    },
  };
}
function request(originalUrl = url("Genève [hospital]")) {
  return { method: "GET", originalUrl, headers: {}, rawBody: Buffer.alloc(0) };
}

test("normalization matches Android suffix, Unicode, case and whitespace", () => {
  assert.equal(
    queryFromUrl(url("  HÔPITAL   Genève [hospital] ")),
    "hôpital genève",
  );
  assert.equal(queryFromUrl(url("Ｇｅｎèｖｅ")), "genève");
});
test("reject missing, repeated, malformed, oversized and forwarding parameters", () => {
  for (const q of [
    "",
    " ",
    "a",
    "x".repeat(201),
    "\nGeneva",
    "abc\u200d",
    "https://example.org",
    "foo@bar.org",
    "[clinic]",
    "😀",
  ])
    assert.throws(() => queryFromUrl(url(q)), failure(400), JSON.stringify(q));
  for (const u of [
    "/search",
    "/search?q=Genève",
    url("Geneva") + "&q=Bern",
    url("Geneva") + "&url=https://example.org",
    url("Geneva") + "&limit=2",
    url("Geneva") + "&format=json",
    "/search?q=%ZZ",
  ])
    assert.throws(() => queryFromUrl(u), failure(400), u);
  assert.throws(() => queryFromUrl("x".repeat(2049)), failure(413));
});
test("service caches successful results and empty arrays under hashed keys", async (t) => {
  for (const result of [[hospital], []]) {
    await t.test(
      result.length ? "hospital results" : "empty results",
      async () => {
        const store = fakeStore();
        let calls = 0;
        const service = new HospitalSearchService(store, {
          async search() {
            calls++;
            return result;
          },
        });
        assert.deepEqual(
          await service.search("genève", "a".repeat(64)),
          result,
        );
        assert.deepEqual(
          await service.search("genève", "a".repeat(64)),
          result,
        );
        assert.equal(calls, 1);
        assert.match([...store.values.keys()][0], /^[a-f0-9]{64}$/);
        assert.deepEqual(store.releases, [1000]);
      },
    );
  }
});
test("cache recheck inside acquired lock prevents duplicate upstream work", async () => {
  const store = fakeStore();
  let reads = 0;
  store.cached = async () => (++reads === 1 ? undefined : []);
  assert.deepEqual(
    await new HospitalSearchService(store, {
      search() {
        assert.fail();
      },
    }).search("genève", "a".repeat(64)),
    [],
  );
});
test("busy requests recheck completed identical cache or reject without upstream", async () => {
  const store = fakeStore();
  store.locked = true;
  const service = new HospitalSearchService(store, {
    search() {
      assert.fail();
    },
  });
  await assert.rejects(service.search("genève", "a".repeat(64)), failure(429));
  let reads = 0;
  store.cached = async () => (++reads === 1 ? undefined : []);
  assert.deepEqual(await service.search("genève", "a".repeat(64)), []);
});
test("cache and limiter failures fail closed; failed release never reports success", async (t) => {
  for (const method of [
    "cached",
    "consumeQuota",
    "acquire",
    "save",
    "release",
  ]) {
    await t.test(`storage method: ${method}`, async () => {
      const store = fakeStore();
      let calls = 0;
      store[method] = async () => {
        throw new Error("private data");
      };
      await assert.rejects(
        new HospitalSearchService(store, {
          async search() {
            calls++;
            return [];
          },
        }).search("genève", "a".repeat(64)),
        failure(503, "storage_unavailable"),
      );
      assert.equal(calls, ["save", "release"].includes(method) ? 1 : 0);
    });
  }
});
test("upstream failures never cache, always release, and apply shared cooldown", async (t) => {
  for (const status of [429, 502, 504]) {
    await t.test(`upstream status: ${status}`, async () => {
      const store = fakeStore();
      await assert.rejects(
        new HospitalSearchService(store, {
          async search() {
            throw new SearchError(status, "upstream_failure");
          },
        }).search("genève", "a".repeat(64)),
        failure(status),
      );
      assert.equal(store.values.size, 0);
      assert.deepEqual(store.releases, [status === 429 ? 60000 : 10000]);
    });
  }
});
test("HTTP returns parser-compatible JSON, enforces auth, methods and request bounds", async (t) => {
  const service = new HospitalSearchService(fakeStore(), {
    async search() {
      return [hospital];
    },
  });
  const handler = createHandler(service, async () => "a".repeat(64));
  const ok = response();
  await handler(request(), ok);
  assert.equal(ok.statusCode, 200);
  assert.deepEqual(ok.body, [hospital]);
  assert.equal(ok.headers["Cache-Control"], "no-store");
  for (const [req, status] of [
    [{ ...request(), method: "POST" }, 405],
    [request("/search"), 400],
    [request("x".repeat(2049)), 413],
    [{ ...request(), rawBody: Buffer.from("x") }, 413],
    [{ ...request(), headers: { "content-length": "100000" } }, 413],
    [{ ...request(), headers: { "transfer-encoding": "chunked" } }, 413],
  ]) {
    await t.test(`request: ${JSON.stringify(req)}`, async () => {
      const res = response();
      await handler(req, res);
      assert.equal(res.statusCode, status);
    });
  }
  for (const status of [401, 403]) {
    await t.test(`authentication status: ${status}`, async () => {
      const res = response();
      await createHandler(service, async () => {
        throw new SearchError(status, "unauthorized");
      })(request(), res);
      assert.equal(res.statusCode, status);
    });
  }
  const sanitized = response();
  await createHandler(service, async () => {
    throw new Error("secret");
  })(request(), sanitized);
  assert.deepEqual(sanitized.body, { error: { code: "service_unavailable" } });
});
test("HTTP rejects overlapping work without an in-memory queue", async () => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const handler = createHandler(
    {
      async search() {
        await gate;
        return [];
      },
    },
    async () => "a".repeat(64),
  );
  const first = handler(request(), response());
  await Promise.resolve();
  const busy = response();
  await handler(request(), busy);
  assert.equal(busy.statusCode, 429);
  release();
  await first;
});
test("Nominatim sends fixed URL, identity, bounded contract and projects Android fields", async () => {
  let calls = 0;
  const client = new NominatimClient(
    "maintainer@example.org",
    async (u, options) => {
      calls++;
      assert.equal(u.origin, "https://nominatim.openstreetmap.org");
      assert.equal(u.pathname, "/search");
      assert.equal(u.searchParams.get("q"), "genève [hospital]");
      assert.equal(u.searchParams.get("format"), "jsonv2");
      assert.equal(u.searchParams.get("limit"), "10");
      assert.equal(options.headers["Accept-Encoding"], "identity");
      assert.match(
        options.headers["User-Agent"],
        /Oncompanion.*maintainer@example.org/,
      );
      return Response.json([
        { ...hospital, secret: "discard" },
        { ...hospital, type: "clinic" },
      ]);
    },
  );
  assert.deepEqual(await client.search("genève"), [hospital]);
  assert.equal(calls, 1);
  assert.deepEqual(
    await new NominatimClient("a@example.org", async () =>
      Response.json([]),
    ).search("geneva"),
    [],
  );
});
test("upstream 429, 5xx, redirects, malformed results and network failures are sanitized", async () => {
  for (const [fetcher, status, label] of [
    [async () => new Response("", { status: 429 }), 429],
    [async () => new Response("", { status: 503 }), 503],
    [async () => new Response("", { status: 302 }), 502],
    [
      async () => {
        throw new Error("private");
      },
      502,
    ],
    ...[
      {},
      [null],
      [{ ...hospital, lat: "NaN" }],
      [{ ...hospital, lon: "181" }],
      [{ ...hospital, osm_id: 0 }],
      [{ ...hospital, display_name: "" }],
      Array(11).fill(hospital),
    ].map((body) => [
      async () => Response.json(body),
      502,
      JSON.stringify(body),
    ]),
    [async () => new Response("not JSON"), 502],
    [async () => new Response("x".repeat(262145)), 502],
    [async () => new Response(null), 502],
  ])
    await assert.rejects(
      new NominatimClient("a@example.org", fetcher).search("geneva"),
      failure(status),
      `upstream ${label ?? String(fetcher)} expected ${status}`,
    );
  await assert.rejects(
    new NominatimClient("", () => assert.fail()).search("geneva"),
    failure(503),
  );
});
test("upstream timeout aborts the request using virtual time", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const client = new NominatimClient(
    "a@example.org",
    async (_, options) =>
      new Promise((_, reject) =>
        options.signal.addEventListener("abort", () =>
          reject(new Error("aborted")),
        ),
      ),
  );
  const pending = assert.rejects(
    client.search("geneva"),
    failure(504, "upstream_timeout"),
  );
  t.mock.timers.tick(5000);
  await pending;
});

test("Firebase Auth and App Check must both verify and match an allowed app", async () => {
  const { authorizeTokens } = await import("../lib/authorization.js");
  const auth = async () => ({ uid: "patient" });
  const app = async () => ({ appId: "android" });
  await authorizeTokens("Bearer user", "app", ["android"], auth, app);
  for (const [bearer, token, ids, verifyAuth, verifyApp, status] of [
    [undefined, "app", ["android"], auth, app, 401],
    ["Bearer " + "x".repeat(8193), "app", ["android"], auth, app, 401],
    ["Bearer user", undefined, ["android"], auth, app, 403],
    ["Bearer user", "x".repeat(8193), ["android"], auth, app, 403],
    ["Bearer user", "app", [], auth, app, 403],
    ["Bearer user", "app", ["other"], auth, app, 403],
    [
      "Bearer user",
      "app",
      ["android"],
      async () => {
        throw Error("secret");
      },
      app,
      401,
    ],
    [
      "Bearer user",
      "app",
      ["android"],
      auth,
      async () => {
        throw Error("secret");
      },
      403,
    ],
  ])
    await assert.rejects(
      authorizeTokens(bearer, token, ids, verifyAuth, verifyApp),
      failure(status),
      JSON.stringify({ bearer, token, ids, status }),
    );
});

test("deployed entry point preserves conservative scaling and rejects unauthenticated calls", async () => {
  process.env.GCLOUD_PROJECT = "demo-oncompanion-proxy";
  const { hospitalSearch } = await import("../lib/index.js");
  assert.equal(hospitalSearch.__endpoint.platform, "gcfv2");
  assert.equal(hospitalSearch.__endpoint.maxInstances, 1);
  assert.equal(hospitalSearch.__endpoint.concurrency, 1);
  const req = {
    ...request(),
    get() {
      return undefined;
    },
  };
  const res = {
    ...response(),
    on() {
      return this;
    },
  };
  await hospitalSearch(req, res);
  assert.equal(res.statusCode, 401);
  const again = {
    ...response(),
    on() {
      return this;
    },
  };
  await hospitalSearch(req, again);
  assert.equal(again.statusCode, 401);
});

test("core transport makes one wire request for 421, 408, redirects and server errors", async (t) => {
  const { createServer, request: httpRequest } = await import("node:http");
  const { requestOnce } = await import("../lib/https-transport.js");
  let calls = 0;
  let status = 421;
  const server = createServer((req, res) => {
    calls++;
    res.writeHead(status, {
      Location: "/redirect-target",
      "Retry-After": "900",
    });
    res.end("[]");
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  t.after(() => new Promise((resolve) => server.close(resolve)));
  for (status of [421, 408, 302, 503, 204, 304]) {
    const before = calls;
    const response = await requestOnce(
      new URL(`http://127.0.0.1:${server.address().port}/search`),
      {
        headers: { Accept: "application/json" },
        signal: new AbortController().signal,
      },
      httpRequest,
    );
    assert.equal(response.status, status);
    assert.equal(response.headers.get("Retry-After"), "900");
    await response.arrayBuffer();
    assert.equal(calls, before + 1);
  }
  status = 600;
  await assert.rejects(
    requestOnce(
      new URL(`http://127.0.0.1:${server.address().port}/search`),
      { headers: {}, signal: new AbortController().signal },
      httpRequest,
    ),
  );
  assert.equal(calls, 7);
});

test("core transport never retries socket errors and cancels a partially read response", async (t) => {
  const { createServer, request: httpRequest } = await import("node:http");
  const { requestOnce } = await import("../lib/https-transport.js");
  let calls = 0;
  const server = createServer((req, res) => {
    calls++;
    if (req.url === "/reset") req.socket.destroy();
    else {
      res.writeHead(200);
      res.write("[");
    }
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  t.after(
    () =>
      new Promise((resolve) => {
        server.closeAllConnections();
        server.close(resolve);
      }),
  );
  const endpoint = `http://127.0.0.1:${server.address().port}`;
  await assert.rejects(
    requestOnce(
      new URL(endpoint + "/reset"),
      { headers: {}, signal: new AbortController().signal },
      httpRequest,
    ),
  );
  assert.equal(calls, 1);
  const controller = new AbortController();
  const response = await requestOnce(
    new URL(endpoint + "/partial"),
    { headers: {}, signal: controller.signal },
    httpRequest,
  );
  const reader = response.body.getReader();
  assert.equal((await reader.read()).done, false);
  controller.abort();
  await assert.rejects(reader.read());
  assert.equal(calls, 2);
});

test("proxy rejects JavaScript-only coordinate syntax and empty Android fallback names", async () => {
  for (const invalid of [
    {},
    { type: "hospital" },
    { ...hospital, lat: "0b10" },
    { ...hospital, lon: "0o10" },
    { ...hospital, lat: "0x1" },
    { ...hospital, name: "", display_name: " , Genève" },
  ])
    await assert.rejects(
      new NominatimClient("a@example.org", async () =>
        Response.json([invalid]),
      ).search("geneva"),
      failure(502),
      JSON.stringify(invalid),
    );
  const result = await new NominatimClient("a@example.org", async () =>
    Response.json([
      {
        ...hospital,
        name: " ",
        display_name: " Hôpital , Genève",
        lat: " +4.62e1 ",
        lon: " .61e1 ",
      },
    ]),
  ).search("geneva");
  assert.equal(result[0].name, "Hôpital");
  assert.equal(result[0].lat, "+4.62e1");
  assert.equal(result[0].lon, ".61e1");
});

test("upstream Retry-After is enforced in shared cooldown and HTTP response", async (t) => {
  t.mock.timers.enable({
    apis: ["Date"],
    now: Date.parse("2026-10-20T00:00:00Z"),
  });
  for (const status of [429, 503])
    for (const [header, seconds] of [
      ["900", 900],
      ["Tue, 20 Oct 2026 00:30:00 GMT", 1800],
      ["1", 60],
      ["invalid", 60],
      ["99999999999999999999999999", 60],
      ["", 60],
    ]) {
      const store = fakeStore();
      const client = new NominatimClient(
        "a@example.org",
        async () =>
          new Response("", { status, headers: { "Retry-After": header } }),
      );
      const handler = createHandler(
        new HospitalSearchService(store, client),
        async () => "a".repeat(64),
      );
      const res = response();
      await handler(request(), res);
      assert.equal(res.statusCode, status, `${status}: ${header}`);
      assert.equal(
        res.headers["Retry-After"],
        String(seconds),
        `${status}: ${header}`,
      );
      assert.deepEqual(store.releases, [seconds * 1000]);
    }
});

test("real hospital names retain accents and legitimate punctuation", async (t) => {
  for (const name of [
    "Hôpital de l'Île",
    "Hôpital du Valais (HVS)",
    "Kantonsspital St. Gallen",
    "Clinique de La Source",
    "HUG / Cluse-Roseraie",
    "St. Peter & Paul, Zürich",
    "L’Hôpital – Genève".replace("–", "-"),
  ]) {
    await t.test(name, () =>
      assert.equal(queryFromUrl(url(name)), name.toLowerCase()),
    );
  }
});

test("quota applies only after a shared cache miss and before global acquisition", async () => {
  const store = fakeStore();
  let charged = 0;
  store.consumeQuota = async (key) => {
    assert.equal(key, "a".repeat(64));
    charged++;
    if (charged > 1) throw new SearchError(429, "quota_exceeded");
  };
  const service = new HospitalSearchService(store, {
    async search() {
      return [];
    },
  });
  await service.search("geneva", "a".repeat(64));
  await service.search("geneva", "a".repeat(64));
  assert.equal(charged, 1);
  store.acquire = () =>
    assert.fail("rejected quota must not acquire global limiter");
  await assert.rejects(
    service.search("bern", "a".repeat(64)),
    failure(429, "quota_exceeded"),
  );
  assert.equal(charged, 2);
});

test("authenticated handler forwards only permitted hospital parameters and headers upstream", async (t) => {
  const { createServer, request: httpRequest } = await import("node:http");
  const { requestOnce } = await import("../lib/https-transport.js");
  let wire;
  const server = createServer((req, res) => {
    wire = { url: req.url, headers: req.headers, method: req.method };
    res.end("[]");
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  let calls = 0;
  const client = new NominatimClient(
    "maintainer@example.org",
    async (upstream, options) => {
      calls++;
      assert.deepEqual(
        [...upstream.searchParams],
        [
          ["q", "hôpital de l'île [hospital]"],
          ["format", "jsonv2"],
          ["limit", "10"],
        ],
      );
      assert.deepEqual(options.headers, {
        "User-Agent":
          "Oncompanion/1.0 (+https://github.com/Oncompanion/Oncompanion; maintainer@example.org)",
        Accept: "application/json",
        "Accept-Language": "en",
        "Accept-Encoding": "identity",
      });
      assert.deepEqual(Object.keys(options).sort(), ["headers", "signal"]);
      return requestOnce(
        new URL(
          `http://127.0.0.1:${server.address().port}${upstream.pathname}${upstream.search}`,
        ),
        options,
        httpRequest,
      );
    },
  );
  const req = request(url("Hôpital de l'Île"));
  req.headers = {
    authorization: "Bearer private-id-token",
    "x-firebase-appcheck": "private-app-token",
  };
  req.patientProfile = { uid: "private-uid", diagnosis: "private-profile" };
  const res = response();
  await createHandler(
    new HospitalSearchService(fakeStore(), client),
    async () => "a".repeat(64),
  )(req, res);
  assert.equal(res.statusCode, 200);
  assert.equal(calls, 1);
  assert.equal(wire.method, "GET");
  assert.equal(
    wire.url,
    `/search?${new URLSearchParams({ q: "hôpital de l'île [hospital]", format: "jsonv2", limit: "10" })}`,
  );
  assert.deepEqual(Object.keys(wire.headers).sort(), [
    "accept",
    "accept-encoding",
    "accept-language",
    "connection",
    "host",
    "user-agent",
  ]);
  assert.equal(wire.headers.connection, "close");
  assert.equal(wire.headers.host, `127.0.0.1:${server.address().port}`);
});

test("HTTP 503 Retry-After has a sanitized minimum and preserves longer hints", async (t) => {
  for (const [hint, expected] of [
    [undefined, "60"],
    [0, "60"],
    [-1, "60"],
    [900, "900"],
  ]) {
    await t.test(String(hint), async () => {
      const res = response();
      await createHandler(
        {
          async search() {
            throw new SearchError(503, "service_unavailable", hint);
          },
        },
        async () => "a".repeat(64),
      )(request(), res);
      assert.equal(res.statusCode, 503);
      assert.equal(res.headers["Retry-After"], expected);
      assert.deepEqual(res.body, { error: { code: "service_unavailable" } });
    });
  }
});

test("TTL configuration covers only cache and quota expiry, never the global lock", async () => {
  const { readFile } = await import("node:fs/promises");
  const config = JSON.parse(
    await readFile(new URL("../../firebase.json", import.meta.url)),
  );
  assert.equal(config.firestore.indexes, "firestore.indexes.json");
  const indexes = JSON.parse(
    await readFile(new URL("../../firestore.indexes.json", import.meta.url)),
  );
  assert.deepEqual(
    indexes.fieldOverrides,
    ["hospitalSearchCache", "hospitalSearchQuota"].map((collectionGroup) => ({
      collectionGroup,
      fieldPath: "expiresAt",
      ttl: true,
      indexes: [],
    })),
  );
});

test("UID quota keys are stable, secret-dependent hashes with no raw identity", async () => {
  const { quotaKeyForUid } = await import("../lib/authorization.js");
  const key = quotaKeyForUid("private-uid", "secret".repeat(8));
  assert.match(key, /^[a-f0-9]{64}$/);
  assert.equal(key, quotaKeyForUid("private-uid", "secret".repeat(8)));
  assert.notEqual(key, quotaKeyForUid("another-uid", "secret".repeat(8)));
  assert.notEqual(key, quotaKeyForUid("private-uid", "rotated".repeat(8)));
  assert.throws(() => quotaKeyForUid("private-uid", ""));
  assert.throws(() => quotaKeyForUid("", "secret".repeat(8)));
});

test("invalid or unauthenticated requests never spend quota or acquire the global limiter", async (t) => {
  for (const status of [400, 401, 403])
    await t.test(String(status), async () => {
      const store = fakeStore();
      store.consumeQuota = () => assert.fail("Rejected request spent quota");
      store.acquire = () => assert.fail("Rejected request acquired limiter");
      const handler = createHandler(
        new HospitalSearchService(store, {
          search() {
            assert.fail();
          },
        }),
        async () => {
          throw new SearchError(status, "rejected");
        },
      );
      const res = response();
      await handler(status === 400 ? request(url("[clinic]")) : request(), res);
      assert.equal(res.statusCode, status);
    });
});
