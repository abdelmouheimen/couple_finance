import createClient from "openapi-fetch";
import { appConfig } from "@/shared/config/env";
import type { paths } from "./generated/schema";

/** Typed API client; types are generated from api/openapi.yaml (`npm run generate:api`). */
export function createApiClient(baseUrl: string = appConfig.apiBaseUrl) {
    return createClient<paths>({ baseUrl });
}

export type { paths, components } from "./generated/schema";
