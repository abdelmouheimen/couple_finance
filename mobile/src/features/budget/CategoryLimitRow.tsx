import { Pressable, StyleSheet, View } from "react-native";
import { type CategoryVisual } from "@/features/expense/categoryVisual";
import type { components } from "@/shared/api/client";
import { strings } from "@/shared/i18n/strings";
import { formatMoney, type Money as DeviceMoney, spokenMoney } from "@/shared/money/money";
import { Icon, IconTile, ProgressBar, StatusPill, Text, useColors } from "@/shared/ui";
import { minTouchTarget, spacing } from "@/shared/ui/theme/tokens";

type Money = components["schemas"]["Money"];
type Consumption = components["schemas"]["LimitConsumption"];

function asMoney(m: Money | undefined): DeviceMoney | null {
    return m?.amount !== undefined && m.currency !== undefined
        ? { amount: m.amount, currency: m.currency }
        : null;
}

/**
 * One category limit. With the server consumption (BR-BUD-03/05/06, GET only) it shows spent / limit, the
 * percentage and a bar, all verbatim from the API (BR-MON-08/09: no arithmetic on device); a status other
 * than "on track" is also written, so it never relies on colour alone. Without consumption only the limit
 * is shown.
 */
export function CategoryLimitRow({
    name,
    limit,
    consumption,
    visual,
    onPress,
}: {
    name: string;
    limit: Money;
    consumption?: Consumption | undefined;
    visual: CategoryVisual;
    /** Opens the expenses of this category for the period. */
    onPress?: (() => void) | undefined;
}) {
    const colors = useColors();
    const limitMoney = asMoney(limit);
    const spent = asMoney(consumption?.consumed);
    const complete =
        consumption && limitMoney && spent && consumption.status && consumption.percentage;
    const body = (
        <>
            <IconTile icon={visual.icon} color={visual.color} />
            <View style={styles.main}>
                <View style={styles.head}>
                    <View style={styles.flex}>
                        <Text weight="medium" numberOfLines={1}>
                            {name}
                        </Text>
                        <Text variant="caption" tone="secondary" tabular>
                            {complete
                                ? strings.budget.categorySpentOf(
                                      formatMoney(spent),
                                      formatMoney(limitMoney),
                                  )
                                : limitMoney
                                  ? formatMoney(limitMoney)
                                  : ""}
                        </Text>
                    </View>
                    {complete ? (
                        <Text variant="caption" weight="medium" tabular>
                            {strings.percent(consumption.percentage!)}
                        </Text>
                    ) : null}
                    {onPress ? <Icon name="chevron" color={colors.textSecondary} /> : null}
                </View>
                {complete ? (
                    <>
                        <ProgressBar
                            compact
                            percent={Number(consumption.percentage)}
                            percentText={consumption.percentage!}
                            status={consumption.status!}
                            valueText={strings.budget.categoryRowA11y(
                                name,
                                spokenMoney(spent),
                                spokenMoney(limitMoney),
                            )}
                        />
                        {consumption.status !== "ON_TRACK" ? (
                            <StatusPill status={consumption.status!} />
                        ) : null}
                    </>
                ) : null}
            </View>
        </>
    );
    if (!onPress) {
        return (
            <View testID={complete ? "category-limit-row" : undefined} style={styles.row}>
                {body}
            </View>
        );
    }
    return (
        <Pressable
            testID="category-limit-row"
            accessibilityRole="button"
            accessibilityHint={strings.dashboard.categoryHint}
            onPress={onPress}
            style={styles.row}
        >
            {body}
        </Pressable>
    );
}

const styles = StyleSheet.create({
    flex: { flex: 1 },
    row: {
        minHeight: minTouchTarget,
        flexDirection: "row",
        alignItems: "flex-start",
        gap: spacing.md,
        paddingVertical: spacing.sm,
    },
    main: { flex: 1, gap: spacing.sm },
    head: { flexDirection: "row", alignItems: "center", gap: spacing.sm },
});
