import { useSyncExternalStore } from "react";
import { type SessionManager, type SessionStatus } from "./session";

export function useSessionStatus(session: SessionManager): SessionStatus {
    return useSyncExternalStore(session.subscribe, session.getStatus, session.getStatus);
}
