// Co-authored-by: ChatGPT <noreply@openai.com>
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getAppCheck } from "firebase-admin/app-check";
import { getFirestore } from "firebase-admin/firestore";
import { defineString } from "firebase-functions/params";
import { onRequest } from "firebase-functions/v2/https";
import { authorizeTokens } from "./authorization.js";
import { FirestoreSearchStore } from "./firestore-store.js";
import { createHandler } from "./handler.js";
import { NominatimClient } from "./nominatim.js";
import { HospitalSearchService } from "./service.js";

const contact = defineString("NOMINATIM_CONTACT_EMAIL", { default: "" });
const appIds = defineString("HOSPITAL_APP_IDS", { default: "" });
let handler: ReturnType<typeof createHandler> | undefined;

/** Authenticated and App Check protected explicit hospital lookup; no production deployment implied. */
export const hospitalSearch = onRequest(
  {
    region: "europe-west1",
    maxInstances: 1,
    concurrency: 1,
    minInstances: 0,
    timeoutSeconds: 30,
    memory: "256MiB",
    cors: false,
    invoker: "public",
  },
  async (request, response) => {
    if (!handler) {
      initializeApp();
      handler = createHandler(
        new HospitalSearchService(
          new FirestoreSearchStore(getFirestore()),
          process.env.FUNCTIONS_EMULATOR === "true"
            ? {
                async search() {
                  return [];
                },
              }
            : new NominatimClient(contact.value()),
        ),
        async (req) => {
          await authorizeTokens(
            req.get("Authorization"),
            req.get("X-Firebase-AppCheck"),
            appIds
              .value()
              .split(",")
              .map((id) => id.trim())
              .filter(Boolean),
            (token) => getAuth().verifyIdToken(token),
            (token) => getAppCheck().verifyToken(token),
          );
        },
      );
    }
    await handler(request, response);
  },
);
