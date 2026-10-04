import { useQuery } from "@tanstack/react-query";
import { createContext, type ReactNode, useContext, useMemo } from "react";
import { ApiError } from "@/shared/api/problem";
import { type BudgetPeriod, currentBudgetPeriod } from "./currentPeriod";
import { fetchCurrentHousehold, type Household } from "./householdApi";

export const HOUSEHOLD_QUERY_KEY = ["household", "me"] as const;

export type HouseholdState =
    | { kind: "loading" }
    | { kind: "error"; error: unknown }
    | { kind: "emailNotVerified" }
    | { kind: "none" }
    | {
          kind: "ready";
          household: Household;
          /** BR-HH-10: a DISSOLVED household is read-only; mutation entry points must be disabled. */
          readOnly: boolean;
          /** Current budget period computed in the household timezone (the app's only period source). */
          currentPeriod: BudgetPeriod;
      };

export interface HouseholdContextValue {
    state: HouseholdState;
    refetch: () => void;
}

const HouseholdContext = createContext<HouseholdContextValue | null>(null);

interface Props {
    children: ReactNode;
    /** Injectable clock (tests). */
    now?: () => Date;
    /** False while signed out: no request is made. */
    enabled?: boolean;
}

/** Resolves the caller's household once and exposes it (id, name, currency, timezone, period) app-wide. */
export function HouseholdProvider({ children, now = () => new Date(), enabled = true }: Props) {
    const query = useQuery({
        queryKey: HOUSEHOLD_QUERY_KEY,
        queryFn: fetchCurrentHousehold,
        retry: false,
        enabled,
    });
    const value = useMemo<HouseholdContextValue>(() => {
        const refetch = () => void query.refetch();
        const data = query.data;
        if (query.isError) {
            const error = query.error;
            const unverified = error instanceof ApiError && error.code === "EMAIL_NOT_VERIFIED";
            return {
                refetch,
                state: unverified ? { kind: "emailNotVerified" } : { kind: "error", error },
            };
        }
        if (data === undefined) return { refetch, state: { kind: "loading" } };
        if (data === null) return { refetch, state: { kind: "none" } };
        return {
            refetch,
            state: {
                kind: "ready",
                household: data,
                readOnly: data.status === "DISSOLVED",
                currentPeriod: currentBudgetPeriod(data.periodStartDay, data.timezone, now()),
            },
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps -- `now` is a stable injected clock
    }, [query.data, query.isError, query.error, query.refetch]);
    return <HouseholdContext.Provider value={value}>{children}</HouseholdContext.Provider>;
}

export function useHouseholdState(): HouseholdContextValue {
    const ctx = useContext(HouseholdContext);
    if (!ctx) throw new Error("HouseholdProvider is missing");
    return ctx;
}

/** The ready household for feature screens; throws if used before the household is resolved. */
export function useHouseholdContext() {
    const { state } = useHouseholdState();
    if (state.kind !== "ready") throw new Error("Household context is not ready");
    return state;
}

/** True when mutations must be disabled. Safe outside a provider (no household yet => false). */
export function useIsReadOnly(): boolean {
    const ctx = useContext(HouseholdContext);
    return ctx?.state.kind === "ready" && ctx.state.readOnly;
}
