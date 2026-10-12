import { type ReactNode, useEffect, useState } from "react";
import { AccessibilityInfo } from "react-native";
import { chartPalettes, type ColorScheme, type Palette, palettes } from "./tokens";

const SCHEME: ColorScheme = "dark";

/** Kept as the single theming seam (gallery, tests); the approved design has one dark scheme. */
export function ThemeProvider({ children }: { scheme?: ColorScheme; children: ReactNode }) {
    return <>{children}</>;
}

/** The approved palette. Dark only: the OS colour scheme is deliberately not followed. */
export function useColors(): Palette & { scheme: ColorScheme } {
    return { ...palettes[SCHEME], scheme: SCHEME };
}

/** Categorical chart colours. */
export function useChartColors() {
    return chartPalettes[SCHEME];
}

/** True when the OS asks for reduced motion; animations must then be skipped. */
export function useReducedMotion(): boolean {
    const [reduced, setReduced] = useState(false);
    useEffect(() => {
        let active = true;
        void AccessibilityInfo.isReduceMotionEnabled().then((v) => active && setReduced(v));
        const sub = AccessibilityInfo.addEventListener("reduceMotionChanged", setReduced);
        return () => {
            active = false;
            sub.remove();
        };
    }, []);
    return reduced;
}
