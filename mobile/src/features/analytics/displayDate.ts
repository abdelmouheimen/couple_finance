/** Display only (no period math): the ISO business date in the device locale, timezone-neutral. */
export function displayDate(iso: string): string {
    const [y, m, d] = iso.split("-").map(Number);
    if (!y || !m || !d) return iso;
    return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString(undefined, {
        dateStyle: "medium",
        timeZone: "UTC",
    });
}
