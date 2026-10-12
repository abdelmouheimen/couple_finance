/**
 * API responses for the visual-regression harness ONLY. They reproduce the figures shown in the approved
 * reference (design/reference) so the screenshots can be compared with it. They are served by Playwright
 * route interception; the app itself contains no sample data.
 */
const eur = (amount) => ({ amount, currency: "EUR" });
const id = (n) => `019a0000-0000-7000-8000-${String(n).padStart(12, "0")}`;

const CAT = {
    GROCERIES: id(1),
    HOUSING: id(2),
    TRANSPORT: id(4),
    RESTAURANTS: id(5),
};
const ME = "01920000-0000-7000-8000-00000000a001";
const HOUSEHOLD = "01920000-0000-7000-8000-00000000b001";

const system = [
    ["GROCERIES", "cart", 1],
    ["HOUSING", "home", 2],
    ["UTILITIES", "bolt", 3],
    ["TRANSPORT", "car", 4],
    ["RESTAURANTS", "restaurant", 5],
    ["HEALTH", "heart", 6],
    ["LEISURE", "gamepad", 7],
    ["SHOPPING", "bag", 8],
    ["TRAVEL", "plane", 9],
    ["CHILDREN", "child", 10],
    ["GIFTS", "gift", 11],
    ["OTHER", "dots", 12],
];

export const categories = {
    items: system.map(([code, icon, order]) => ({
        id: id(order),
        systemCode: code,
        icon,
        color: "#9E9E9E",
        type: "SYSTEM",
        archived: false,
        sortOrder: order,
        version: 0,
    })),
};

export const household = {
    id: HOUSEHOLD,
    name: "Abdel & Sarah",
    currency: "EUR",
    timezone: "Europe/Paris",
    periodStartDay: 1,
    status: "ACTIVE",
    version: 1,
};

export const me = {
    id: ME,
    email: "abdel@example.test",
    displayName: "Abdel",
    emailVerified: true,
};

const expense = (n, merchant, date, amount, categoryId) => ({
    id: `01920000-0000-7000-8000-0000000c${String(n).padStart(4, "0")}`,
    kind: "EXPENSE",
    merchant,
    date,
    amount: eur(amount),
    sharingType: "SHARED",
    paidByUserId: ME,
    createdBy: ME,
    items: [{ id: `item-${n}`, amount: eur(amount), categoryId }],
    version: 0,
    createdAt: `${date}T10:00:00Z`,
});

export const expenses = [
    expense(1, "Carrefour", "2026-10-04", "10.00", CAT.GROCERIES),
    expense(2, "Uber", "2026-10-02", "12.50", CAT.TRANSPORT),
    expense(3, "Lidl", "2026-10-01", "35.20", CAT.GROCERIES),
    expense(4, "Starbucks", "2026-10-01", "5.90", CAT.RESTAURANTS),
    expense(5, "Essence", "2026-10-01", "60.00", CAT.TRANSPORT),
];

export const expensePage = (scope) => ({
    items: scope === "PERSONAL" ? [] : expenses,
    totals: { count: expenses.length, net: eur("123.60"), scope },
});

/** Home of the reference: 990,00 € left of 1 000,00 €, 10,00 € spent, all in Courses. */
export const periodAnalytics = (scope) => ({
    scope,
    periodStart: "2026-10-01",
    periodEnd: "2026-11-01",
    total: eur(scope === "PERSONAL" ? "0.00" : "10.00"),
    ...(scope === "PERSONAL"
        ? {}
        : {
              budget: {
                  limit: eur("1000.00"),
                  consumed: eur("10.00"),
                  remaining: eur("990.00"),
                  percentage: "1.0",
                  status: "ON_TRACK",
              },
              previousPeriod: {
                  difference: eur("0.00"),
                  changePercentage: "0.0",
                  total: eur("10.00"),
              },
          }),
    categories:
        scope === "PERSONAL"
            ? []
            : [{ categoryId: CAT.GROCERIES, total: eur("10.00"), percentage: "100.0" }],
    negativeCategories: [],
    members: [],
});

export const dailyCumulative = (scope) => ({
    scope,
    periodStart: "2026-10-01",
    periodEnd: "2026-11-01",
    ...(scope === "PERSONAL" ? {} : { limit: eur("1000.00") }),
    points: [
        { date: "2026-10-01", cumulative: eur("0.00") },
        { date: "2026-10-04", cumulative: eur("10.00") },
        { date: "2026-10-12", cumulative: eur("10.00") },
    ],
});

const limit = (categoryId, amount, consumed, remaining, percentage) => ({
    categoryId,
    limit: eur(amount),
    consumption: {
        consumed: eur(consumed),
        remaining: eur(remaining),
        percentage,
        status: "ON_TRACK",
    },
});

/** Budget of the reference. Percentages keep the server precision (one decimal, BR-MON-09). */
export const budget = {
    periodStart: "2026-10-01",
    version: 3,
    overallLimit: eur("1000.00"),
    overallConsumption: {
        consumed: eur("10.00"),
        remaining: eur("990.00"),
        percentage: "1.0",
        status: "ON_TRACK",
    },
    categoryLimits: [
        limit(CAT.GROCERIES, "150.00", "10.00", "140.00", "6.7"),
        limit(CAT.TRANSPORT, "200.00", "0.00", "200.00", "0.0"),
        limit(CAT.RESTAURANTS, "150.00", "0.00", "150.00", "0.0"),
        limit(CAT.HOUSING, "300.00", "0.00", "300.00", "0.0"),
    ],
};

/** Receipt vault (DOC-002): metadata only; page images are served by the storage route of the harness. */
const receiptPage = (position) => ({
    position,
    contentType: "image/jpeg",
    sizeBytes: 182000,
    widthPx: 1200,
    heightPx: 2000,
});
export const receipts = {
    items: [
        {
            id: "01920000-0000-7000-8000-0000000d0001",
            sourceKind: "IMAGES",
            state: "UPLOADED",
            totalSizeBytes: 364000,
            pages: [receiptPage(1), receiptPage(2)],
            createdAt: "2026-10-04T12:32:00Z",
            version: 0,
        },
        {
            id: "01920000-0000-7000-8000-0000000d0002",
            sourceKind: "PDF",
            state: "UPLOADED",
            totalSizeBytes: 98000,
            pages: [receiptPage(1)],
            createdAt: "2026-10-01T08:10:00Z",
            version: 0,
        },
    ],
};
export const STORAGE = "http://localhost:9000";
export const receiptPageUrl = (id, position) => ({
    url: `${STORAGE}/couplefinance-receipts/receipts/${id}-${position}?X-Amz-Expires=300&X-Amz-Signature=visual`,
    expiresAt: "2026-10-12T08:05:00Z",
    contentType: "image/jpeg",
});
