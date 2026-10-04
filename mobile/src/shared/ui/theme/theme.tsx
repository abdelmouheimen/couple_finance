import { createContext, type ReactNode, useContext, useEffect, useState } from "react";
import { AccessibilityInfo, useColorScheme } from "react-native";
import { type ColorScheme, type Palette, palettes } from "./tokens";

const SchemeOverride = createContext<ColorScheme | null>(null);

/** Optional: force a scheme (gallery, tests). Without it the system scheme is used. */
export function ThemeProvider({ scheme, children }: { scheme: ColorScheme; children: ReactNode }) {
    return <SchemeOverride.Provider value={scheme}>{children}</SchemeOverride.Provider>;
}

export function useColors(): Palette & { scheme: ColorScheme } {
    const override = useContext(SchemeOverride);
    const system = useColorScheme();
    const scheme: ColorScheme = override ?? (system === "dark" ? "dark" : "light");
    return { ...palettes[scheme], scheme };
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
