/**
 * The labelled "Add expense" action floats only where adding is the natural next step: Home and
 * Expenses. It is hidden on Budget (it would compete with "Edit budget") and on Settings.
 */
export function showAddExpenseAction(pathname: string): boolean {
    return pathname === "/" || pathname === "/expenses" || pathname.startsWith("/expenses/");
}
