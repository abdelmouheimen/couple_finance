import { useQuery } from "@tanstack/react-query";
import { StyleSheet, View } from "react-native";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { formatMoney } from "@/shared/money/money";
import { Card, ErrorState, Skeleton, Text, useChartColors } from "@/shared/ui";
import { spacing } from "@/shared/ui/theme/tokens";
import { type AnalyticsScope, dailyCumulativeKey, fetchDailyCumulative } from "./analyticsApi";
import { toSeriesModel } from "./chartModel";
import { LineChart } from "./charts/LineChart";
import { displayDate } from "./displayDate";

/**
 * "Spending over time": the authoritative daily cumulative series of the period (BR-ANA-01), with the
 * overall budget limit as a reference line when the API provides one (never for PERSONAL, BR-BUD-02).
 */
export function SpendingOverTime({
    periodStart,
    scope,
}: {
    periodStart: string;
    scope: AnalyticsScope;
}) {
    const chart = useChartColors();
    const series = useQuery({
        queryKey: dailyCumulativeKey(periodStart, scope),
        queryFn: () => fetchDailyCumulative(periodStart, scope),
        retry: false,
    });
    const scopeLabel = strings.scope[scope];

    let body;
    if (series.isError) {
        body = (
            <ErrorState
                message={errorMessage(series.error)}
                onRetry={() => void series.refetch()}
            />
        );
    } else if (!series.data) {
        body = <Skeleton variant="card" />;
    } else {
        const model = toSeriesModel(series.data);
        if (!model) {
            body = <Text tone="secondary">{strings.dashboard.overTimeEmpty}</Text>;
        } else {
            const first = model.first!;
            const last = model.last!;
            const limit = model.limit ? formatMoney(model.limit.money) : undefined;
            const label =
                model.points.length === 1
                    ? strings.dashboard.overTimeA11ySingle(
                          scopeLabel,
                          displayDate(last.date),
                          formatMoney(last.money),
                      )
                    : strings.dashboard.overTimeA11y(
                          scopeLabel,
                          displayDate(first.date),
                          displayDate(last.date),
                          formatMoney(last.money),
                          limit,
                      );
            body = (
                <>
                    <LineChart
                        testID="spending-chart"
                        model={model}
                        lineColor={chart.series[0]!}
                        accessibilityLabel={label}
                        firstLabel={displayDate(first.date)}
                        lastLabel={displayDate(last.date)}
                    />
                    <View style={styles.legend}>
                        <Text variant="caption" tone="secondary">
                            {strings.dashboard.overTimeSeries}
                            {" — "}
                            {formatMoney(last.money)}
                        </Text>
                        {limit ? (
                            <Text variant="caption" tone="secondary" testID="spending-chart-limit">
                                {strings.dashboard.overTimeBudget(limit)}
                            </Text>
                        ) : null}
                    </View>
                </>
            );
        }
    }

    return (
        <Card>
            <Text variant="title" accessibilityRole="header">
                {strings.dashboard.overTimeTitle}
            </Text>
            <Text variant="caption" tone="secondary" bold>
                {scopeLabel}
            </Text>
            {body}
        </Card>
    );
}

const styles = StyleSheet.create({
    legend: { gap: spacing.xs },
});
