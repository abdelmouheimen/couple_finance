import { StyleSheet, View } from "react-native";
import type { components } from "@/shared/api/client";
import { strings } from "@/shared/i18n/strings";
import { formatMoney, type Money as DeviceMoney, spokenMoney } from "@/shared/money/money";
import { ListRow, MoneyText, ProgressBar, Text } from "@/shared/ui";
import { spacing } from "@/shared/ui/theme/tokens";

type Money = components["schemas"]["Money"];
type Consumption = components["schemas"]["LimitConsumption"];

function asMoney(m: Money | undefined): DeviceMoney | null {
    return m?.amount !== undefined && m.currency !== undefined
        ? { amount: m.amount, currency: m.currency }
        : null;
}

/**
 * One category limit. With the server consumption (BR-BUD-03/05/06, GET only) it shows spent / limit, the
 * percentage and a bar whose state is also written and iconised (ProgressBar), all verbatim from the API
 * (BR-MON-08/09: no arithmetic on device). Without consumption only the limit is shown.
 */
export function CategoryLimitRow({
    name,
    limit,
    consumption,
}: {
    name: string;
    limit: Money;
    consumption?: Consumption | undefined;
}) {
    const limitMoney = asMoney(limit);
    const spent = asMoney(consumption?.consumed);
    if (!consumption || !limitMoney || !spent || !consumption.status || !consumption.percentage) {
        return (
            <ListRow
                title={name}
                trailing={limitMoney ? <MoneyText money={limitMoney} variant="body" /> : undefined}
            />
        );
    }
    return (
        <View testID="category-limit-row" style={styles.row}>
            <Text bold>{name}</Text>
            <Text tone="secondary" tabular>
                {strings.budget.categorySpentOf(formatMoney(spent), formatMoney(limitMoney))}
            </Text>
            <ProgressBar
                percent={Number(consumption.percentage)}
                percentText={consumption.percentage}
                status={consumption.status}
                valueText={strings.budget.categoryRowA11y(
                    name,
                    spokenMoney(spent),
                    spokenMoney(limitMoney),
                )}
            />
        </View>
    );
}

const styles = StyleSheet.create({ row: { gap: spacing.xs, paddingVertical: spacing.sm } });
