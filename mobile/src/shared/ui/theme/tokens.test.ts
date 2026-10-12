import { readdirSync, readFileSync, statSync } from "fs";
import { join, relative, sep } from "path";
import { contrastRatio } from "./contrast";
import { icons } from "./icons";
import {
    categoryColors,
    chartPalettes,
    contrastPairs,
    darkPalette,
    fontSize,
    lineHeight,
    radius,
    size,
    spacing,
} from "./tokens";

const contract = JSON.parse(
    readFileSync(join(__dirname, "..", "..", "..", "..", "..", "design", "tokens.json"), "utf8"),
) as {
    color: Record<string, string>;
    category: Record<string, string>;
    chart: { series: string[]; other: string };
    font: { size: Record<string, number>; lineHeight: Record<string, number> };
    spacing: Record<string, number>;
    radius: Record<string, number>;
    size: Record<string, number>;
};

describe("code tokens mirror the approved design contract (design/tokens.json)", () => {
    test("palette", () => {
        const c = contract.color;
        expect(darkPalette).toMatchObject({
            background: c.background,
            card: c.card,
            surface: c.surface,
            border: c.border,
            text: c.textPrimary,
            textSecondary: c.textSecondary,
            primary: c.primary,
            primaryDark: c.primaryDark,
            onPrimary: c.onPrimary,
            danger: c.error,
            onDanger: c.onError,
            success: c.success,
            warning: c.warning,
            overlay: c.overlay,
        });
    });
    test("reference swatches of the Design System panel are kept verbatim", () => {
        expect(contract.color).toMatchObject({
            primary: "#22C55E",
            primaryDark: "#16A34A",
            card: "#111827",
            surface: "#1F2937",
            border: "#374151",
            textPrimary: "#F9FAFB",
            textSecondary: "#9CA3AF",
            success: "#22C55E",
            warning: "#F59E0B",
            error: "#EF4444",
        });
    });
    test("category and chart colours", () => {
        expect({ ...categoryColors }).toEqual(contract.category);
        expect(chartPalettes.dark).toEqual(contract.chart);
    });
    test("type scale (H1 32 bold, H2 24, H3 20, body 16, caption 14)", () => {
        expect({ ...fontSize }).toEqual(contract.font.size);
        expect({ ...lineHeight }).toEqual(contract.font.lineHeight);
        expect(fontSize).toMatchObject({ h1: 32, h2: 24, h3: 20, body: 16, caption: 14 });
    });
    test("spacing, radii and sizes", () => {
        expect({ ...spacing }).toEqual(contract.spacing);
        expect({ ...radius }).toEqual(contract.radius);
        expect({ ...size }).toEqual(contract.size);
    });
});

describe("token contrast (WCAG AA)", () => {
    for (const pair of contrastPairs) {
        test(`${pair.fg} on ${pair.bg} >= ${pair.min}`, () => {
            expect(
                contrastRatio(darkPalette[pair.fg], darkPalette[pair.bg]),
            ).toBeGreaterThanOrEqual(pair.min);
        });
    }
});

describe("chart colours (graphical objects, WCAG 1.4.11)", () => {
    test("every chart series colour has >= 3:1 against the card", () => {
        const { series, other } = chartPalettes.dark;
        for (const color of [...series, other]) {
            expect(contrastRatio(color, darkPalette.card)).toBeGreaterThanOrEqual(3);
        }
    });
    test("every category tile colour keeps >= 3:1 against the card", () => {
        const { onCategory: _ignored, ...tiles } = categoryColors;
        for (const color of Object.values(tiles)) {
            expect(contrastRatio(color, darkPalette.card)).toBeGreaterThanOrEqual(3);
        }
    });
});

describe("icons are vectors, never emoji or text glyphs", () => {
    test("every icon is made of path/circle/rect shapes", () => {
        for (const shapes of Object.values(icons)) {
            expect(shapes.length).toBeGreaterThan(0);
            for (const shape of shapes) expect(["path", "circle", "rect"]).toContain(shape.t);
        }
    });
});

describe("no hard-coded visual values outside tokens", () => {
    const root = join(__dirname, "..", "..", "..", "..");
    const roots = [
        join(root, "app"),
        join(root, "src", "shared", "ui"),
        join(root, "src", "shared", "forms"),
        join(root, "src", "features"),
    ];
    const allowed = ["tokens.ts", "contrast.ts", "icons.ts"];

    function files(dir: string): string[] {
        return readdirSync(dir).flatMap((name) => {
            const p = join(dir, name);
            if (statSync(p).isDirectory()) return files(p);
            const source = /\.(ts|tsx)$/.test(name) && !/\.test\.tsx?$/.test(name);
            return source && !allowed.includes(name) ? [p] : [];
        });
    }

    const rules: [string, RegExp][] = [
        ["hex color", /#[0-9a-fA-F]{3,8}\b/],
        ["rgb(a) color", /rgba?\(/],
        ["literal color", /[cC]olor\s*:\s*["']/],
        ["literal font size", /fontSize\s*:\s*\d/],
        ["literal font family", /fontFamily\s*:\s*["']/],
        ["literal opacity", /opacity\s*:\s*[0-9.]/],
        ["literal spacing/radius", /(padding|margin|gap|[rR]adius|borderWidth)\w*\s*:\s*-?[1-9]/],
        // Emoji / pictographs used as interface icons.
        ["emoji", /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u],
    ];

    for (const file of roots.flatMap(files)) {
        test(`${relative(root, file).split(sep).join("/")} uses tokens only`, () => {
            const source = readFileSync(file, "utf8");
            for (const [name, re] of rules) {
                expect({ name, hit: re.test(source) }).toEqual({ name, hit: false });
            }
        });
    }
});
