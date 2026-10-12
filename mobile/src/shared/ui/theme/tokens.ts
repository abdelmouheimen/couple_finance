/**
 * Single source of design tokens in code (MOBILE-002, MOBILE-009). It mirrors the approved design
 * contract `design/tokens.json` (checked by `tokens.test.ts`). No hard-coded colors, spacing, radii or
 * font sizes may appear outside this file; the token lint test enforces it.
 *
 * The approved design is dark only ("Dark mode uniquement"): there is a single palette.
 */
import { Platform } from "react-native";

export interface Palette {
    background: string;
    /** Cards, fields, sheets and the navigation bar (design "Card"). */
    card: string;
    /** Raised or inset neutral areas: progress tracks, tiles, pressed chips (design "Surface"). */
    surface: string;
    border: string;
    text: string;
    textSecondary: string;
    primary: string;
    primaryDark: string;
    onPrimary: string;
    /** Design "Error". */
    danger: string;
    onDanger: string;
    success: string;
    warning: string;
    overlay: string;
    skeleton: string;
}

export const darkPalette: Palette = {
    background: "#0B0F0E",
    card: "#111827",
    surface: "#1F2937",
    border: "#374151",
    text: "#F9FAFB",
    textSecondary: "#9CA3AF",
    primary: "#22C55E",
    primaryDark: "#16A34A",
    onPrimary: "#0B0F0E",
    danger: "#EF4444",
    onDanger: "#0B0F0E",
    success: "#22C55E",
    warning: "#F59E0B",
    overlay: "rgba(0,0,0,0.6)",
    skeleton: "#1F2937",
};

export type ColorScheme = "dark";

export const palettes: Record<ColorScheme, Palette> = { dark: darkPalette };

/**
 * Category tile colours. The reference defines groceries, transport, restaurants, housing and "other";
 * the remaining system categories extend the same family (recorded in design/DESIGN-SPEC.md, A-07).
 */
export const categoryColors = {
    groceries: "#22C55E",
    transport: "#3B82F6",
    restaurants: "#F97316",
    housing: "#EF4444",
    other: "#8B7DA2",
    utilities: "#EAB308",
    health: "#EC4899",
    leisure: "#A855F7",
    shopping: "#14B8A6",
    travel: "#06B6D4",
    children: "#FB7185",
    gifts: "#6366F1",
    onCategory: "#F9FAFB",
} as const;
export type CategoryColorName = Exclude<keyof typeof categoryColors, "onCategory">;

/**
 * Categorical chart series colours. Never the only carrier of meaning: every slice/line is also named
 * or labelled in text. Each colour keeps >= 3:1 against the card (WCAG 1.4.11), checked by the token
 * contrast test; `other` is the neutral "everything else" slice.
 */
export const chartPalettes: Record<ColorScheme, { series: readonly string[]; other: string }> = {
    dark: {
        series: ["#22C55E", "#3B82F6", "#F97316", "#EF4444", "#A855F7"],
        other: "#8B7DA2",
    },
};

/** Foreground/background pairs that must satisfy WCAG AA (checked by the token contrast test). */
export const contrastPairs: readonly {
    fg: keyof Palette;
    bg: keyof Palette;
    min: 4.5 | 3;
}[] = [
    { fg: "text", bg: "background", min: 4.5 },
    { fg: "text", bg: "card", min: 4.5 },
    { fg: "text", bg: "surface", min: 4.5 },
    { fg: "textSecondary", bg: "background", min: 4.5 },
    { fg: "textSecondary", bg: "card", min: 4.5 },
    { fg: "onPrimary", bg: "primary", min: 4.5 },
    { fg: "onDanger", bg: "danger", min: 4.5 },
    { fg: "primary", bg: "background", min: 4.5 },
    { fg: "primary", bg: "card", min: 4.5 },
    { fg: "danger", bg: "background", min: 4.5 },
    { fg: "danger", bg: "card", min: 4.5 },
    { fg: "success", bg: "card", min: 4.5 },
    { fg: "warning", bg: "card", min: 4.5 },
];

export const spacing = {
    none: 0,
    xs: 4,
    sm: 8,
    md: 12,
    lg: 16,
    /** Horizontal screen gutter. */
    screen: 20,
    xl: 24,
    xxl: 32,
} as const;
export const radius = { sm: 8, md: 12, lg: 16, pill: 999 } as const;
export const borderWidth = { hairline: 1, thick: 2 } as const;

export const fontSize = { h1: 32, h2: 24, h3: 20, body: 16, caption: 14, label: 12 } as const;
export const lineHeight = { h1: 40, h2: 32, h3: 28, body: 24, caption: 20, label: 16 } as const;
export const fontWeight = { regular: "400", medium: "500", semibold: "600", bold: "700" } as const;
export type FontWeightName = keyof typeof fontWeight;

/** Inter, one loaded face per weight (a custom family carries its own weight on Android). */
export const fontFamily: Record<FontWeightName, string> = {
    regular: "Inter_400Regular",
    medium: "Inter_500Medium",
    semibold: "Inter_600SemiBold",
    bold: "Inter_700Bold",
};

export const defaultWeight: Record<keyof typeof fontSize, FontWeightName> = {
    h1: "bold",
    h2: "semibold",
    h3: "semibold",
    body: "regular",
    caption: "regular",
    label: "medium",
};

/** Tabular figures keep amounts aligned (BR-MON display). */
export const tabularFigures: ["tabular-nums"] = ["tabular-nums"];

/** Maximum text scaling honoured for amounts (200 % per accessibility requirements). */
export const maxFontScale = 2;

/** iOS HIG 44 pt / Android Material 48 dp. */
export const minTouchTarget = Platform.select({ ios: 44, default: 48 });

export const size = {
    controlSm: 44,
    controlMd: 48,
    controlLg: 56,
    field: 48,
    iconSm: 16,
    iconMd: 20,
    iconLg: 24,
    iconTile: 40,
    avatar: 40,
    avatarLg: 56,
    tabBar: 80,
    fab: 56,
    progressBar: 8,
    ring: 88,
    ringStroke: 10,
    donut: 160,
    donutStroke: 28,
    statusDot: 10,
    legendDot: 10,
    badge: 24,
    shutter: 72,
    thumbnail: 96,
} as const;

export const iconSize = { sm: size.iconSm, md: size.iconMd, lg: size.iconLg } as const;
/** Stroke of the outline icon set, in the 24-unit icon grid. */
export const iconStrokeWidth = 2;

/** The approved design is flat: depth comes from the card/surface steps, not from shadows. */
export const elevation = {
    none: { shadowOpacity: 0, elevation: 0 },
    floating: {
        shadowColor: "#000000",
        shadowOpacity: 0.4,
        shadowRadius: 16,
        shadowOffset: { width: 0, height: 8 },
        elevation: 8,
    },
} as const;

/** Chart geometry (device-independent units); charts resize to their container width. */
export const chartHeight = 160;
export const chartStrokeWidth = 2;
export const chartPointRadius = 4;

/** Opacity tokens (disabled controls are exempt from WCAG contrast; the intent is explicit here). */
export const opacity = { disabled: 0.5, pressed: 0.85, opaque: 1 } as const;

/** Sheets never grow past this share of the window height; their content scrolls. */
export const sheetMaxHeightRatio = 0.9;
/** Error toasts stay long enough to be read (WCAG 2.2.1); success toasts are brief. */
export const errorToastDurationMs = 8000;
/** Aspect ratio (width / height) of the receipt viewfinder of the scan screen. */
export const viewfinderAspectRatio = 0.58;
/** Length of a viewfinder corner bracket. */
export const viewfinderCorner = 28;
