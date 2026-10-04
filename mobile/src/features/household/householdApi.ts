import type { components } from "@/shared/api/client";
import { api } from "@/shared/api/instance";
import { ApiError } from "@/shared/api/problem";
import { unwrap } from "@/shared/api/unwrap";

export type Household = components["schemas"]["Household"];
export type Invitation = components["schemas"]["Invitation"];
export type CreatedInvitation = components["schemas"]["CreatedInvitation"];
export type CreateHouseholdInput = components["schemas"]["CreateHouseholdRequest"];

/** Current household, or `null` for HOUSEHOLD_NOT_FOUND (the user has none: route to creation). */
export async function fetchCurrentHousehold(): Promise<Household | null> {
    try {
        return await unwrap(api.GET("/api/v1/households/me"));
    } catch (e) {
        if (e instanceof ApiError && e.status === 404 && e.code === "HOUSEHOLD_NOT_FOUND") {
            return null;
        }
        throw e;
    }
}

export function createHousehold(input: CreateHouseholdInput): Promise<Household> {
    return unwrap(api.POST("/api/v1/households", { body: input }));
}

export function listInvitations(): Promise<Invitation[]> {
    return unwrap(api.GET("/api/v1/households/me/invitations"));
}

export function createInvitation(): Promise<CreatedInvitation> {
    return unwrap(api.POST("/api/v1/households/me/invitations"));
}

export async function revokeInvitation(id: string): Promise<void> {
    await unwrap(
        api.DELETE("/api/v1/households/me/invitations/{id}", { params: { path: { id } } }),
    );
}
