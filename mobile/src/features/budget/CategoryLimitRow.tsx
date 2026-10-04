import type { components } from "@/shared/api/client";
import { ListRow, MoneyText } from "@/shared/ui";

type Money = components["schemas"]["Money"];

/** One category limit: a single labelled element (name + limit). The limit is shown as returned. */
export function CategoryLimitRow({ name, limit }: { name: string; limit: Money }) {
    const money =
        limit.amount !== undefined && limit.currency !== undefined
            ? { amount: limit.amount, currency: limit.currency }
            : null;
    return (
        <ListRow
            title={name}
            trailing={money ? <MoneyText money={money} variant="body" /> : undefined}
        />
    );
}
