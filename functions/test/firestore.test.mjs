// Co-authored-by: ChatGPT <noreply@openai.com>
import assert from "node:assert/strict";
import { test, after } from "node:test";
import { Firestore, Timestamp } from "firebase-admin/firestore";
import { FirestoreSearchStore } from "../lib/firestore-store.js";
import { HospitalSearchService } from "../lib/service.js";

// Refuse production access even if developer credentials exist.
if (
  !process.env.FIRESTORE_EMULATOR_HOST ||
  !/^127\.0\.0\.1:\d+$/.test(process.env.FIRESTORE_EMULATOR_HOST)
)
  throw new Error("Run only against a loopback Firestore emulator.");
const projectId = "demo-oncompanion-proxy";
const firstDb = new Firestore({ projectId });
const secondDb = new Firestore({ projectId });
const firstStore = new FirestoreSearchStore(firstDb);
const secondStore = new FirestoreSearchStore(secondDb);
const control = firstDb.doc("hospitalSearchControl/nominatim");
async function reset() {
  await firstDb.recursiveDelete(firstDb.collection("hospitalSearchCache"));
  await control.delete();
  await firstDb.recursiveDelete(firstDb.collection("hospitalSearchQuota"));
}
async function ageCooldown() {
  // Advance persisted test state instead of sleeping or substituting a process-local limiter.
  await control.set({ releasedAt: Timestamp.fromMillis(0), cooldownMs: 1000 });
}
after(async () => {
  await firstDb.terminate();
  await secondDb.terminate();
});

test("different queries across independent clients are serialized with a shared cooldown", async () => {
  await reset();
  let calls = 0;
  let finish;
  let entered;
  const pending = new Promise((resolve) => {
    finish = resolve;
  });
  const started = new Promise((resolve) => {
    entered = resolve;
  });
  const client = {
    async search() {
      calls++;
      entered();
      await pending;
      return [];
    },
  };
  const first = new HospitalSearchService(firstStore, client);
  const second = new HospitalSearchService(secondStore, client);
  const result = first.search("geneva", "a".repeat(64));
  await started;
  const attempts = await Promise.allSettled(
    Array.from({ length: 8 }, (_, i) =>
      second.search(`city ${i}`, "a".repeat(64)),
    ),
  );
  assert(
    attempts.every((r) => r.status === "rejected" && r.reason.status === 429),
  );
  assert.equal(calls, 1);
  finish();
  assert.deepEqual(await result, []);
  assert.equal(await secondStore.acquire("too-soon"), false);
  const released = (await control.get()).data();
  assert(released.releasedAt instanceof Timestamp);
  assert.equal(released.cooldownMs, 1000);
  await ageCooldown();
  assert.deepEqual(await second.search("lausanne", "a".repeat(64)), []);
  assert.equal(calls, 2);
});
test("atomic acquisition admits exactly one owner across independent Firestore clients", async () => {
  await reset();
  const winners = await Promise.all(
    Array.from({ length: 12 }, (_, i) =>
      (i % 2 ? firstStore : secondStore).acquire(`owner-${i}`),
    ),
  );
  assert.equal(winners.filter(Boolean).length, 1);
});
test("concurrent identical queries never duplicate upstream work and share cached empty results", async () => {
  await reset();
  let calls = 0;
  let finish;
  let entered;
  const pending = new Promise((resolve) => {
    finish = resolve;
  });
  const started = new Promise((resolve) => {
    entered = resolve;
  });
  const client = {
    async search() {
      calls++;
      entered();
      await pending;
      return [];
    },
  };
  const first = new HospitalSearchService(firstStore, client);
  const second = new HospitalSearchService(secondStore, client);
  const result = first.search("geneva", "a".repeat(64));
  await started;
  await assert.rejects(
    second.search("geneva", "a".repeat(64)),
    (e) => e.status === 429,
  );
  finish();
  await result;
  assert.deepEqual(await second.search("geneva", "a".repeat(64)), []);
  assert.equal(calls, 1);
});
test("expired cache entries refresh while fresh entries are shared", async () => {
  await reset();
  let calls = 0;
  const client = {
    async search() {
      calls++;
      return [];
    },
  };
  await new HospitalSearchService(firstStore, client).search(
    "geneva",
    "a".repeat(64),
  );
  const cache = await firstDb.collection("hospitalSearchCache").get();
  assert.equal(cache.size, 1);
  assert.deepEqual(Object.keys(cache.docs[0].data()).sort(), [
    "expiresAt",
    "results",
  ]);
  assert.match(cache.docs[0].id, /^[a-f0-9]{64}$/);
  await new HospitalSearchService(secondStore, client).search(
    "geneva",
    "a".repeat(64),
  );
  assert.equal(calls, 1);
  await cache.docs[0].ref.update({ expiresAt: Timestamp.fromMillis(0) });
  await ageCooldown();
  await new HospitalSearchService(secondStore, client).search(
    "geneva",
    "a".repeat(64),
  );
  assert.equal(calls, 2);
});
test("lock never expires, owner cannot be replaced, corrupt and future state fail closed", async () => {
  await reset();
  assert.equal(await firstStore.acquire("crashed"), true);
  await control.update({ releasedAt: Timestamp.fromMillis(0) });
  assert.equal(await secondStore.acquire("replacement"), false);
  await assert.rejects(
    secondStore.release("replacement", 1000),
    /Lost limiter ownership/,
  );
  assert.equal((await control.get()).get("owner"), "crashed");
  await control.set({ cooldownMs: 0 });
  await assert.rejects(firstStore.acquire("x"), /Invalid limiter state/);
  await control.set({
    releasedAt: Timestamp.fromMillis(Date.now() + 3600000),
    cooldownMs: 1000,
  });
  assert.equal(await firstStore.acquire("x"), false);
});
test("malformed cache and limiter state cause controlled errors without upstream", async () => {
  await reset();
  await firstDb.doc("hospitalSearchCache/bad").set({ results: [] });
  await assert.rejects(firstStore.cached("bad"), /Invalid cache record/);
  await control.set({ cooldownMs: 0 });
  let calls = 0;
  await assert.rejects(
    new HospitalSearchService(secondStore, {
      async search() {
        calls++;
        return [];
      },
    }).search("geneva", "a".repeat(64)),
    (e) => e.status === 503,
  );
  assert.equal(calls, 0);
});
test("unchanged rules deny cache and control access to anonymous and authenticated clients", async () => {
  const host = process.env.FIRESTORE_EMULATOR_HOST;
  const b64 = (value) =>
    Buffer.from(JSON.stringify(value)).toString("base64url");
  const token = `${b64({ alg: "none", typ: "JWT" })}.${b64({ aud: projectId, iss: `https://securetoken.google.com/${projectId}`, sub: "patient", user_id: "patient", iat: Math.floor(Date.now() / 1000), exp: Math.floor(Date.now() / 1000) + 3600, firebase: { sign_in_provider: "google.com" } })}.`;
  // Prove that this test token is recognized as signed in before asserting backend denials.
  const profile = firstDb.doc("users/patient");
  await profile.set({ firstName: "Emulator fixture" });
  const profileUrl = `http://${host}/v1/projects/${projectId}/databases/(default)/documents/users/patient`;
  assert.equal(
    (await fetch(profileUrl, { headers: { Authorization: `Bearer ${token}` } }))
      .status,
    200,
  );
  assert.equal((await fetch(profileUrl)).status, 403);
  await profile.delete();
  for (const path of [
    "hospitalSearchCache/example",
    "hospitalSearchControl/nominatim",
    "hospitalSearchQuota/example",
  ]) {
    const endpoint = `http://${host}/v1/projects/${projectId}/databases/(default)/documents/${path}`;
    for (const headers of [{}, { Authorization: `Bearer ${token}` }]) {
      assert.equal((await fetch(endpoint, { headers })).status, 403);
      assert.equal(
        (
          await fetch(endpoint, {
            method: "PATCH",
            headers: { ...headers, "Content-Type": "application/json" },
            body: JSON.stringify({
              fields: { owner: { stringValue: "attacker" } },
            }),
          })
        ).status,
        403,
      );
    }
  }
});

test("unknown acquisition commit outcome leaves the lock held without upstream work", async () => {
  await reset();
  let calls = 0;
  const uncertainStore = {
    consumeQuota: firstStore.consumeQuota.bind(firstStore),
    cached: firstStore.cached.bind(firstStore),
    save: firstStore.save.bind(firstStore),
    release: firstStore.release.bind(firstStore),
    async acquire(owner) {
      assert.equal(await firstStore.acquire(owner), true);
      throw new Error("Commit acknowledgement lost");
    },
  };
  await assert.rejects(
    new HospitalSearchService(uncertainStore, {
      async search() {
        calls++;
        return [];
      },
    }).search("geneva", "a".repeat(64)),
    (e) => e.status === 503,
  );
  assert.equal(calls, 0);
  assert.equal(await secondStore.acquire("replacement"), false);
});

test("failed release stays safe both before and after an unknown commit acknowledgement", async () => {
  for (const committed of [false, true]) {
    await reset();
    let calls = 0;
    const uncertainStore = {
      consumeQuota: firstStore.consumeQuota.bind(firstStore),
      cached: firstStore.cached.bind(firstStore),
      save: firstStore.save.bind(firstStore),
      acquire: firstStore.acquire.bind(firstStore),
      async release(owner, cooldown) {
        if (committed) await firstStore.release(owner, cooldown);
        throw new Error("Release failed");
      },
    };
    await assert.rejects(
      new HospitalSearchService(uncertainStore, {
        async search() {
          calls++;
          return [];
        },
      }).search("geneva", "a".repeat(64)),
      (e) => e.status === 503,
    );
    assert.equal(calls, 1);
    assert.deepEqual(
      await new HospitalSearchService(secondStore, {
        search() {
          assert.fail();
        },
      }).search("geneva", "a".repeat(64)),
      [],
    );
    assert.equal(await secondStore.acquire("replacement"), false);
    const state = (await control.get()).data();
    if (committed) assert(state.releasedAt instanceof Timestamp);
    else assert.equal(typeof state.owner, "string");
  }
});

test("rolling quota boundaries, expiry and independent users use Firestore server time", async () => {
  await reset();
  const key = "b".repeat(64);
  const ref = firstDb.doc(`hospitalSearchQuota/${key}`);
  for (let i = 0; i < 10; i++) await firstStore.consumeQuota(key);
  await assert.rejects(
    secondStore.consumeQuota(key),
    (e) => e.code === "quota_exceeded",
  );
  let state = (await ref.get()).data();
  assert.equal(state.misses.length, 10);
  assert(state.expiresAt instanceof Timestamp);
  assert.equal(
    state.expiresAt.toMillis() - state.misses.at(-1).toMillis(),
    86400_000,
  );
  await secondStore.consumeQuota("c".repeat(64));
  const now = (await ref.get()).readTime.toMillis();
  // Fifty misses outside the hourly window still exhaust the daily window.
  await ref.set({
    misses: Array(50).fill(Timestamp.fromMillis(now - 7200_000)),
    expiresAt: Timestamp.fromMillis(now + 86400_000),
  });
  await assert.rejects(
    firstStore.consumeQuota(key),
    (e) => e.code === "quota_exceeded",
  );
  await ref.update({
    misses: Array(49).fill(Timestamp.fromMillis(now - 7200_000)),
  });
  await firstStore.consumeQuota(key);
  await assert.rejects(
    secondStore.consumeQuota(key),
    (e) => e.code === "quota_exceeded",
  );
  // Logical expiry works even if TTL deletion has not happened yet.
  await ref.set({
    misses: Array(50).fill(Timestamp.fromMillis(now - 86400_000)),
    expiresAt: Timestamp.fromMillis(0),
  });
  await secondStore.consumeQuota(key);
  assert.equal((await ref.get()).get("misses").length, 1);
  // Exactly one hour old entries leave the hourly window, but remain in the day.
  await ref.set({
    misses: Array(10).fill(Timestamp.fromMillis(now - 3600_000)),
    expiresAt: Timestamp.fromMillis(now + 86400_000),
  });
  await firstStore.consumeQuota(key);
  assert.equal((await ref.get()).get("misses").length, 11);
  await assert.rejects(firstStore.consumeQuota("raw-uid"), /Invalid quota key/);
  await ref.set({ misses: ["corrupt"] });
  await assert.rejects(firstStore.consumeQuota(key), /Invalid quota state/);
});

test("concurrent quota checks across clients cannot exceed hourly or daily limits", async (t) => {
  for (const existing of [0, 49])
    await t.test(`existing daily misses: ${existing}`, async () => {
      await reset();
      const key = "d".repeat(64);
      const ref = firstDb.doc(`hospitalSearchQuota/${key}`);
      const now = (await ref.get()).readTime.toMillis();
      await ref.set({
        misses: Array(existing).fill(Timestamp.fromMillis(now - 7200_000)),
      });
      const outcomes = await Promise.allSettled(
        Array.from({ length: 14 }, (_, i) =>
          (i % 2 ? firstStore : secondStore).consumeQuota(key),
        ),
      );
      assert.equal(
        outcomes.filter((r) => r.status === "fulfilled").length,
        existing ? 1 : 10,
      );
      for (const result of outcomes.filter((r) => r.status === "rejected"))
        assert.equal(result.reason.code, "quota_exceeded");
      assert.equal((await ref.get()).get("misses").length, existing ? 50 : 10);
    });
});

test("release retries recover transient failures and ambiguous acknowledgements without another upstream request", async (t) => {
  for (const committed of [false, true])
    await t.test(`first release committed: ${committed}`, async () => {
      await reset();
      let attempts = 0;
      let calls = 0;
      const logs = [];
      const store = {
        cached: firstStore.cached.bind(firstStore),
        save: firstStore.save.bind(firstStore),
        consumeQuota: firstStore.consumeQuota.bind(firstStore),
        acquire: firstStore.acquire.bind(firstStore),
        async release(owner, cooldown) {
          attempts++;
          if (attempts === 1) {
            if (committed) await firstStore.release(owner, cooldown);
            throw new Error("private query/token must not be logged");
          }
          await firstStore.release(owner, cooldown);
        },
      };
      const service = new HospitalSearchService(
        store,
        {
          async search() {
            calls++;
            return [];
          },
        },
        (...args) => logs.push(args),
      );
      await service.search("geneva", "a".repeat(64));
      assert.equal(attempts, 2);
      assert.equal(calls, 1);
      assert.deepEqual(logs, [[]]);
      assert.equal(await secondStore.acquire("too-soon"), false);
      await ageCooldown();
      await new HospitalSearchService(secondStore, {
        async search() {
          calls++;
          return [];
        },
      }).search("bern", "a".repeat(64));
      assert.equal(calls, 2);
    });
});
