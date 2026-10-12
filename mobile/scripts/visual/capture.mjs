/**
 * Visual validation harness (MOBILE-009). Renders the app (Expo web, `npx expo start --web`) in a fixed
 * Android reference viewport, serves the API from ./fixtures.mjs, captures every screen, measures the
 * acceptance criteria of design/ACCEPTANCE.md and writes reference-versus-implementation comparisons.
 *
 *   node scripts/visual/capture.mjs [--url http://localhost:8081] [--out ../design/screenshots]
 *
 * The reference image is read only; nothing here ever writes to design/reference.
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright-core";
import { PNG } from "pngjs";
import * as fx from "./fixtures.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const arg = (name, fallback) => {
    const i = process.argv.indexOf(`--${name}`);
    return i > 0 ? process.argv[i + 1] : fallback;
};
const BASE = arg("url", "http://localhost:8081");
const OUT = resolve(arg("out", join(here, "..", "..", "..", "design", "screenshots")));
const REFERENCE = resolve(
    here,
    "..",
    "..",
    "..",
    "design",
    "reference",
    "a_wide_high_fidelity_ui_ux_design_mockup_collage.png",
);

/** Fixed reference viewport: Google Pixel 7 (Android), 412 x 915 dp. */
export const VIEWPORT = { width: 412, height: 915 };
const SCALE = 2;
const API = "http://localhost:8080";

/** Screen rectangles of the six phones inside the reference collage (pixels, measured). */
const REFERENCE_SCREENS = {
    home: { x: 13, y: 12, w: 271, h: 686 },
    expenses: { x: 299, y: 12, w: 273, h: 686 },
    scan: { x: 585, y: 12, w: 245, h: 686 },
    review: { x: 843, y: 12, w: 249, h: 686 },
    budget: { x: 1103, y: 12, w: 225, h: 686 },
    settings: { x: 1335, y: 12, w: 199, h: 686 },
};

function json(body, status = 200) {
    return {
        status,
        contentType: "application/json",
        body: JSON.stringify(body),
    };
}

function apiResponse(url) {
    const u = new URL(url);
    const path = u.pathname;
    const scope = u.searchParams.get("scope") ?? "HOUSEHOLD";
    if (path === "/api/v1/auth/login") {
        return json({
            accessToken: "visual-access",
            refreshToken: "visual-refresh",
            expiresIn: 900,
        });
    }
    if (path === "/api/v1/me") return json(fx.me);
    if (path === "/api/v1/households/me") return json(fx.household);
    if (path === "/api/v1/households/me/invitations") return json([]);
    if (path === "/api/v1/categories") return json(fx.categories);
    if (path.endsWith("/daily-cumulative")) return json(fx.dailyCumulative(scope));
    if (path.startsWith("/api/v1/analytics/periods/")) return json(fx.periodAnalytics(scope));
    if (path === "/api/v1/expenses") return json(fx.expensePage(scope));
    if (path.startsWith("/api/v1/budgets/")) return json(fx.budget);
    return json({ status: 404, code: "NOT_FOUND", title: "Not found" }, 404);
}

// ---------- PNG helpers ----------

function crop(png, { x, y, w, h }) {
    const out = new PNG({ width: w, height: h });
    PNG.bitblt(png, out, x, y, w, h, 0, 0);
    return out;
}

function resize(png, width, height) {
    const out = new PNG({ width, height });
    for (let j = 0; j < height; j++) {
        for (let i = 0; i < width; i++) {
            const sx = Math.min(png.width - 1, Math.floor((i * png.width) / width));
            const sy = Math.min(png.height - 1, Math.floor((j * png.height) / height));
            const s = (sy * png.width + sx) * 4;
            const d = (j * width + i) * 4;
            for (let c = 0; c < 4; c++) out.data[d + c] = png.data[s + c];
        }
    }
    return out;
}

function sideBySide(left, right, gap = 24) {
    const height = Math.max(left.height, right.height);
    const out = new PNG({ width: left.width + right.width + gap, height });
    out.data.fill(0);
    for (let i = 3; i < out.data.length; i += 4) out.data[i] = 255;
    PNG.bitblt(left, out, 0, 0, left.width, left.height, 0, 0);
    PNG.bitblt(right, out, 0, 0, right.width, right.height, left.width + gap, 0);
    return out;
}

function pixel(png, x, y) {
    const i = (Math.round(y) * png.width + Math.round(x)) * 4;
    return `#${[0, 1, 2].map((k) => png.data[i + k].toString(16).padStart(2, "0")).join("")}`.toUpperCase();
}

// ---------- capture ----------

async function main() {
    mkdirSync(OUT, { recursive: true });
    const browser = await chromium.launch({ channel: "chrome", headless: true });
    const context = await browser.newContext({
        viewport: VIEWPORT,
        deviceScaleFactor: SCALE,
        isMobile: true,
        hasTouch: true,
        locale: "fr-FR",
        timezoneId: "Europe/Paris",
        colorScheme: "dark",
    });
    const page = await context.newPage();
    await page.clock.setFixedTime(new Date("2026-10-12T10:00:00+02:00"));
    await page.route(`${API}/**`, (route) => route.fulfill(apiResponse(route.request().url())));
    const errors = [];
    page.on("pageerror", (e) => errors.push(String(e)));
    page.on("console", (m) => {
        if (m.type() === "error") errors.push(m.text().slice(0, 300));
    });
    // The Expo dev error toast would cover the navigation bar; errors are collected above instead.
    await page.addInitScript(() => {
        const style = document.createElement("style");
        style.textContent = "#error-toast{display:none!important}";
        document.addEventListener("DOMContentLoaded", () => document.head.appendChild(style));
    });

    const results = [];
    const settle = async () => {
        await page.waitForLoadState("networkidle");
        await page.waitForTimeout(700);
    };

    // Measures a few anchors as fractions of the viewport height (see design/ACCEPTANCE.md).
    const anchors = async (texts) => {
        const out = {};
        for (const [key, locator] of Object.entries(texts)) {
            const box = await locator.first().boundingBox();
            out[key] = box
                ? {
                      top: +(box.y / VIEWPORT.height).toFixed(3),
                      height: Math.round(box.height),
                      left: Math.round(box.x),
                      width: Math.round(box.width),
                  }
                : null;
        }
        return out;
    };

    const shoot = async (name, measure = {}) => {
        await settle();
        const file = join(OUT, `${name}.png`);
        await page.screenshot({ path: file });
        results.push({ name, file, anchors: await anchors(measure) });
    };

    // Sign in through the real form (the API is the fixture server above).
    await page.goto(BASE);
    await page.getByLabel("E-mail").first().fill("abdel@example.test");
    await page.getByLabel("Mot de passe").first().fill("visual-password");
    await page.getByRole("button", { name: "Se connecter" }).click();
    await page.getByText("CoupleFinance").first().waitFor();

    const nav = page.getByRole("tablist");
    await shoot("01-home", {
        title: page.getByText("CoupleFinance").first(),
        segmented: page.getByRole("radiogroup").first(),
        hero: page.getByText("Budget mensuel"),
        breakdown: page.getByText("Répartition par catégorie"),
        nav,
        fab: page.getByRole("button", { name: "Ajouter une dépense" }),
    });
    await page.getByRole("tab", { name: "Dépenses" }).click();
    await shoot("02-expenses", {
        title: page.getByRole("heading", { name: "Dépenses" }),
        // The field box (the input itself starts after the search icon).
        search: page.getByPlaceholder("Rechercher un commerçant...").locator(".."),
        segmented: page.getByRole("radiogroup").first(),
        firstRow: page.getByTestId("expense-list").getByText("Carrefour"),
        nav,
    });
    await page.getByRole("button", { name: "Ajouter une dépense" }).click();
    await page.getByRole("radio", { name: "Scanner" }).click();
    await shoot("03-add-expense-scan", {
        title: page.getByText("Ajouter une dépense").first(),
        segmented: page.getByRole("radiogroup").first(),
        shutter: page.getByRole("button", { name: "Prendre la photo" }),
    });
    await page.getByRole("radio", { name: "Manuel" }).click();
    await shoot("03b-add-expense-manual", {
        title: page.getByText("Ajouter une dépense").first(),
    });
    await page.getByRole("button", { name: "Fermer" }).first().click();
    await page.getByRole("tab", { name: "Budget" }).click();
    await shoot("05-budget", {
        title: page.getByRole("heading", { name: "Budget" }).first(),
        overall: page.getByText("Budget global"),
        byCategory: page.getByText("Par catégorie"),
        edit: page.getByRole("button", { name: "Modifier le budget" }),
        nav,
    });
    await page.getByRole("tab", { name: "Paramètres" }).click();
    await shoot("06-settings", {
        title: page.getByRole("heading", { name: "Paramètres" }),
        household: page.getByText("Abdel & Sarah"),
        profile: page.getByText("Profil"),
        about: page.getByText("À propos"),
        nav,
    });
    // Receipt review: design preview (no receipt API yet; sample values, nothing is sent).
    await page.goto(`${BASE}/design-preview?screen=review`);
    await shoot("04-receipt-review", {
        title: page.getByText("Vérifier les informations"),
        confidence: page.getByText("Confiance de l'analyse"),
        cta: page.getByRole("button", { name: "Créer la dépense" }),
    });

    // Colour sampling (computed from the rendered screenshots).
    const home = PNG.sync.read(readFileSync(join(OUT, "01-home.png")));
    const samples = {
        background: pixel(home, 4 * SCALE, 300 * SCALE),
    };
    const homeAnchors = results.find((r) => r.name === "01-home").anchors;
    if (homeAnchors.hero) {
        const cardTop = homeAnchors.hero.top * VIEWPORT.height;
        samples.card = pixel(home, (VIEWPORT.width - 28) * SCALE, (cardTop + 4) * SCALE);
    }
    if (homeAnchors.segmented) {
        const seg = homeAnchors.segmented;
        samples.primary = pixel(
            home,
            (seg.left + 24) * SCALE,
            (seg.top * VIEWPORT.height + seg.height / 2) * SCALE,
        );
    }

    // Reference-versus-implementation comparisons.
    const reference = PNG.sync.read(readFileSync(REFERENCE));
    const pairs = {
        "01-home": "home",
        "02-expenses": "expenses",
        "03-add-expense-scan": "scan",
        "04-receipt-review": "review",
        "05-budget": "budget",
        "06-settings": "settings",
    };
    for (const [name, key] of Object.entries(pairs)) {
        const shot = PNG.sync.read(readFileSync(join(OUT, `${name}.png`)));
        const ref = crop(reference, REFERENCE_SCREENS[key]);
        const scaled = resize(ref, Math.round((ref.width * shot.height) / ref.height), shot.height);
        writeFileSync(join(OUT, `compare-${name}.png`), PNG.sync.write(sideBySide(scaled, shot)));
    }

    // Measurable acceptance criteria (design/ACCEPTANCE.md).
    const tokens = JSON.parse(
        readFileSync(resolve(here, "..", "..", "..", "design", "tokens.json"), "utf8"),
    );
    const checks = [];
    const check = (id, ok, detail) => checks.push({ id, ok: !!ok, detail });
    check(
        "AC-01 background colour",
        samples.background === tokens.color.background,
        samples.background,
    );
    check("AC-01 card colour", samples.card === tokens.color.card, samples.card);
    check("AC-01 primary colour", samples.primary === tokens.color.primary, samples.primary);
    check("AC-02 no runtime error", errors.length === 0, errors.length);
    const byName = Object.fromEntries(results.map((r) => [r.name, r.anchors]));
    for (const name of ["01-home", "02-expenses", "05-budget", "06-settings"]) {
        const navBox = byName[name].nav;
        check(
            `AC-03 ${name} navigation bar height`,
            navBox && navBox.height === tokens.size.tabBar,
            navBox?.height,
        );
        check(
            `AC-03 ${name} navigation bar docked to the bottom`,
            navBox && Math.abs(navBox.top * VIEWPORT.height + navBox.height - VIEWPORT.height) <= 1,
            navBox?.top,
        );
    }
    const fab = byName["01-home"].fab;
    check(
        "AC-04 central add action size",
        fab && fab.width === tokens.size.fab && fab.height === tokens.size.fab,
        fab?.width,
    );
    check(
        "AC-04 central add action centred",
        fab && Math.abs(fab.left + fab.width / 2 - VIEWPORT.width / 2) <= 1,
        fab?.left,
    );
    const order = {
        "01-home": ["title", "segmented", "hero", "breakdown"],
        "02-expenses": ["title", "search", "segmented", "firstRow"],
        "03-add-expense-scan": ["title", "segmented", "shutter"],
        "04-receipt-review": ["title", "confidence", "cta"],
        "05-budget": ["title", "overall", "byCategory"],
        "06-settings": ["title", "household", "profile", "about"],
    };
    for (const [name, keys] of Object.entries(order)) {
        const tops = keys.map((k) => byName[name][k]?.top ?? NaN);
        check(
            `AC-05 ${name} block order`,
            tops.every((t, i) => i === 0 || t > tops[i - 1]),
            tops.join(" < "),
        );
    }
    for (const [name, key] of [
        ["01-home", "title"],
        ["02-expenses", "title"],
        ["05-budget", "title"],
        ["06-settings", "title"],
        ["01-home", "segmented"],
        ["02-expenses", "search"],
    ]) {
        const box = byName[name][key];
        check(
            `AC-06 ${name} ${key} on the ${tokens.spacing.screen} dp gutter`,
            box && box.left === tokens.spacing.screen,
            box?.left,
        );
    }
    const seg = byName["01-home"].segmented;
    check(
        "AC-07 segmented control spans the content width",
        seg && seg.width === VIEWPORT.width - 2 * tokens.spacing.screen,
        seg?.width,
    );
    const cta = byName["04-receipt-review"].cta;
    check(
        "AC-08 primary call to action height",
        cta && cta.height === tokens.size.controlLg,
        cta?.height,
    );
    const failed = checks.filter((c) => !c.ok);
    for (const c of checks) console.log(`${c.ok ? "PASS" : "FAIL"}  ${c.id}  (${c.detail})`);

    writeFileSync(
        join(OUT, "report.json"),
        JSON.stringify(
            { viewport: VIEWPORT, scale: SCALE, samples, checks, results, errors },
            null,
            2,
        ),
    );
    await browser.close();
    if (failed.length > 0) {
        console.error(`${failed.length} acceptance criteria failed`);
        process.exit(1);
    }
}

main().catch((e) => {
    console.error(e);
    process.exit(1);
});
