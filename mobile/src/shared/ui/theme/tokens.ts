/**
 * Single source of design tokens (MOBILE-002). No hard-coded colors, spacing, radii or font sizes
 * may appear outside this file; `tokens.lint.test.ts` enforces it.
 */
import { Platform } from "react-native";

export interface Palette {
    background: string;
    surface: string;
    surfaceMuted: string;
    border: string;
    text: string;
    textSecondary: string;
    primary: string;
    onPrimary: string;
    secondary: string;
    onSecondary: string;
    danger: string;
    onDanger: string;
    success: string;
    warning: string;
    overlay: string;
    skeleton: string;
}

export const lightPalette: Palette = {
    background: "#F6F7F9",
    surface: "#FFFFFF",
    surfaceMuted: "#E9ECF1",
    border: "#6B7280",
    text: "#111827",
    textSecondary: "#4B5563",
    primary: "#0B6E4F",
    onPrimary: "#FFFFFF",
    secondary: "#E3F1EB",
    onSecondary: "#064632",
    danger: "#B42318",
    onDanger: "#FFFFFF",
    success: "#067647",
    warning: "#8A5300",
    overlay: "rgba(17,24,39,0.5)",
    skeleton: "#DDE1E8",
};

export const darkPalette: Palette = {
    background: "#0E1116",
    surface: "#181C23",
    surfaceMuted: "#242A33",
    border: "#8B94A3",
    text: "#F3F4F6",
    textSecondary: "#B0B8C4",
    primary: "#3DDC97",
    onPrimary: "#04281B",
    secondary: "#1F3A30",
    onSecondary: "#BDF2DA",
    danger: "#FF8A80",
    onDanger: "#3B0A06",
    success: "#4ADE80",
    warning: "#F5B84B",
    overlay: "rgba(0,0,0,0.6)",
    skeleton: "#2B323C",
};

export type ColorScheme = "light" | "dark";
export const palettes: Record<ColorScheme, Palette> = { light: lightPalette, dark: darkPalette };

/** Foreground/background pairs that must satisfy WCAG AA (checked by the token contrast test). */
export const contrastPairs: readonly {
    fg: keyof Palette;
    bg: keyof Palette;
    min: 4.5 | 3;
}[] = [
    { fg: "text", bg: "background", min: 4.5 },
    { fg: "text", bg: "surface", min: 4.5 },
    { fg: "textSecondary", bg: "background", min: 4.5 },
    { fg: "textSecondary", bg: "surface", min: 4.5 },
    { fg: "onPrimary", bg: "primary", min: 4.5 },
    { fg: "onSecondary", bg: "secondary", min: 4.5 },
    { fg: "onDanger", bg: "danger", min: 4.5 },
    { fg: "primary", bg: "surface", min: 4.5 },
    { fg: "danger", bg: "surface", min: 4.5 },
    { fg: "success", bg: "surface", min: 4.5 },
    { fg: "warning", bg: "surface", min: 4.5 },
    { fg: "border", bg: "surface", min: 3 },
    { fg: "border", bg: "background", min: 3 },
    { fg: "text", bg: "surfaceMuted", min: 4.5 },
];

export const spacing = { none: 0, xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 } as const;
export const radius = { sm: 8, md: 12, lg: 20, pill: 999 } as const;
export const borderWidth = { hairline: 1, thick: 2 } as const;

export const fontSize = {
    caption: 13,
    body: 16,
    title: 20,
    headline: 28,
    amount: 32,
} as const;

export const lineHeight = {
    caption: 18,
    body: 24,
    title: 28,
    headline: 36,
    amount: 40,
} as const;

export const fontWeight = { regular: "400", semibold: "600", bold: "700" } as const;

/** Tabular figures keep amounts aligned (BR-MON display). */
export const tabularFigures: ["tabular-nums"] = ["tabular-nums"];

/** Maximum text scaling honoured for amounts (200 % per accessibility requirements). */
export const maxFontScale = 2;

/** iOS HIG 44 pt / Android Material 48 dp. */
export const minTouchTarget = Platform.select({ ios: 44, default: 48 });

export const elevation = {
    none: { shadowOpacity: 0, elevation: 0 },
    card: {
        shadowColor: "#000000",
        shadowOpacity: 0.08,
        shadowRadius: 8,
        shadowOffset: { width: 0, height: 2 },
        elevation: 2,
    },
    floating: {
        shadowColor: "#000000",
        shadowOpacity: 0.2,
        shadowRadius: 12,
        shadowOffset: { width: 0, height: 4 },
        elevation: 6,
    },
} as const;

/** Icon set: text glyphs, decorative only (always paired with a text label or accessibility label). */
export const icons = {
    add: "+",
    close: "✕",
    check: "✓",
    warning: "!",
    error: "×",
    info: "i",
    home: "⌂",
    expenses: "≡",
    budget: "◔",
    chevron: "›",
    settings: "⚙",
    empty: "○",
} as const;
export type IconName = keyof typeof icons;

export const iconSize = { md: 20, lg: 28 } as const;
export const progressBarHeight = 10;
export const fabSize = 56;
/** Opacity tokens (disabled controls are exempt from WCAG contrast; the intent is explicit here). */
export const opacity = { disabled: 0.5, pressed: 0.85, opaque: 1 } as const;
/** Reserved bottom inset so tab content is never hidden under the floating action button. */
export const fabContentInset = fabSize + spacing.xl;

/** Sheets never grow past this share of the window height; their content scrolls. */
export const sheetMaxHeightRatio = 0.9;
/** Error toasts stay long enough to be read (WCAG 2.2.1); success toasts are brief. */
export const errorToastDurationMs = 8000;
