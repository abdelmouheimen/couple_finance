import { readdirSync, readFileSync, statSync } from "fs";
import { join, relative, sep } from "path";
import { contrastRatio } from "./contrast";
import { contrastPairs, palettes, type ColorScheme } from "./tokens";

describe("token contrast (WCAG AA)", () => {
    const schemes: ColorScheme[] = ["light", "dark"];
    for (const scheme of schemes) {
        for (const pair of contrastPairs) {
            test(`${scheme}: ${pair.fg} on ${pair.bg} >= ${pair.min}`, () => {
                const ratio = contrastRatio(palettes[scheme][pair.fg], palettes[scheme][pair.bg]);
                expect(ratio).toBeGreaterThanOrEqual(pair.min);
            });
        }
    }
});

describe("no hard-coded visual values outside tokens", () => {
    const root = join(__dirname, "..", "..", "..", "..");
    const roots = [join(root, "app"), join(root, "src", "shared", "ui")];
    const allowed = ["tokens.ts", "contrast.ts"];

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
        ["literal opacity", /opacity\s*:\s*[0-9.]/],
        ["literal spacing/radius", /(padding|margin|gap|[rR]adius|borderWidth)\w*\s*:\s*[1-9]/],
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
