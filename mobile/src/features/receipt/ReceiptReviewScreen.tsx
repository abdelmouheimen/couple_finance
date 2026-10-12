import { type ReactNode } from "react";
import { Pressable, StyleSheet, View } from "react-native";
import { type CategoryVisual } from "@/features/expense/categoryVisual";
import { strings } from "@/shared/i18n/strings";
import { currencySymbol, type Money } from "@/shared/money/money";
import {
    Button,
    Card,
    Dot,
    Icon,
    IconTile,
    ProgressRing,
    Screen,
    Text,
    useColors,
} from "@/shared/ui";
import { decimalSeparator, toDisplay } from "@/shared/ui/moneyInputParsing";
import { type IconName } from "@/shared/ui/theme/icons";
import {
    borderWidth,
    minTouchTarget,
    radius,
    size,
    spacing,
    tabularFigures,
} from "@/shared/ui/theme/tokens";

/**
 * View model of a receipt draft under review. Provisional: the receipt API (features F4/F5) does not
 * exist yet, so this is a UI contract only, to be replaced by the generated API types when it lands.
 * Every value is display-ready and comes from the caller: this screen computes nothing (BR-MON-08) and
 * never treats an AI value as confirmed data (BR-RCP-02, BR-RCP-10).
 */
export interface ReceiptReviewDraft {
    merchant: string;
    /** Already formatted for display. */
    dateLabel: string;
    amount: Money;
    category?: { label: string; visual: CategoryVisual } | undefined;
    /** Percentages as provided by the analysis, shown verbatim. */
    confidence: {
        overall: string;
        merchant: string;
        date: string;
        amount: string;
        category: string;
    };
    lineCount: number;
}

interface Props {
    draft: ReceiptReviewDraft;
    onBack: () => void;
    onChangePhoto: () => void;
    onOpenLines: () => void;
    onConfirm: () => void | Promise<unknown>;
    /** Receipt picture; a neutral placeholder is shown without one. */
    photo?: ReactNode;
    bottomInset?: number;
}

function Field({
    label,
    value,
    icon,
    leading,
    trailing,
}: {
    label: string;
    value: string;
    icon?: IconName;
    leading?: ReactNode;
    trailing?: ReactNode;
}) {
    const colors = useColors();
    return (
        <View style={styles.field} accessible accessibilityLabel={`${label}, ${value}`}>
            <Text variant="caption" tone="secondary">
                {label}
            </Text>
            <View
                style={[styles.box, { backgroundColor: colors.card, borderColor: colors.border }]}
            >
                {icon ? <Icon name={icon} /> : null}
                {leading}
                <Text style={styles.flex} numberOfLines={1}>
                    {value}
                </Text>
                {trailing}
            </View>
        </View>
    );
}

/**
 * "Vérifier les informations": the review of an analysed receipt before the expense is created, as in
 * the approved design. Presentational only (see {@link ReceiptReviewDraft}).
 */
export function ReceiptReviewScreen({
    draft,
    onBack,
    onChangePhoto,
    onOpenLines,
    onConfirm,
    photo,
    bottomInset,
}: Props) {
    const colors = useColors();
    const check = <Icon name="check" color={colors.primary} />;
    const rows = [
        { label: strings.receipt.confidence.merchant, value: draft.confidence.merchant },
        { label: strings.receipt.confidence.date, value: draft.confidence.date },
        { label: strings.receipt.confidence.amount, value: draft.confidence.amount },
        { label: strings.receipt.confidence.category, value: draft.confidence.category },
    ];
    return (
        <Screen
            title={strings.receipt.reviewTitle}
            onBack={onBack}
            {...(bottomInset !== undefined ? { bottomInset } : {})}
        >
            <View style={styles.photoRow}>
                <View
                    accessible
                    accessibilityRole="image"
                    accessibilityLabel={strings.receipt.photo}
                    style={[styles.photo, { backgroundColor: colors.card }]}
                >
                    {photo ?? <Icon name="receipt" size="lg" color={colors.textSecondary} />}
                </View>
                <Button
                    variant="secondary"
                    icon="camera"
                    label={strings.receipt.changePhoto}
                    onPress={onChangePhoto}
                />
            </View>
            <Field
                label={strings.receipt.merchant}
                value={draft.merchant}
                leading={
                    draft.category ? (
                        <IconTile
                            icon={draft.category.visual.icon}
                            color={draft.category.visual.color}
                        />
                    ) : undefined
                }
                trailing={check}
            />
            <Field
                label={strings.receipt.date}
                value={draft.dateLabel}
                icon="calendar"
                trailing={check}
            />
            <View
                style={styles.field}
                accessible
                accessibilityLabel={`${strings.receipt.amount}, ${toDisplay(
                    draft.amount.amount,
                    decimalSeparator(),
                )} ${draft.amount.currency}`}
            >
                <Text variant="caption" tone="secondary">
                    {strings.receipt.amount}
                </Text>
                <View
                    style={[
                        styles.box,
                        styles.amountBox,
                        { backgroundColor: colors.card, borderColor: colors.border },
                    ]}
                >
                    <Text weight="medium" style={[styles.flex, styles.amount]}>
                        {toDisplay(draft.amount.amount, decimalSeparator())}
                    </Text>
                    <View style={[styles.suffix, { borderLeftColor: colors.border }]}>
                        <Text weight="semibold">{currencySymbol(draft.amount.currency)}</Text>
                    </View>
                </View>
            </View>
            {draft.category ? (
                <Field
                    label={strings.receipt.suggestedCategory}
                    value={draft.category.label}
                    leading={
                        <IconTile
                            icon={draft.category.visual.icon}
                            color={draft.category.visual.color}
                        />
                    }
                    trailing={<Icon name="chevronDown" color={colors.primary} />}
                />
            ) : null}
            <Card style={styles.confidence}>
                <Text variant="caption" weight="semibold">
                    {strings.receipt.confidenceTitle}
                </Text>
                <View style={styles.confidenceBody}>
                    <ProgressRing
                        testID="confidence-ring"
                        percent={Number(draft.confidence.overall)}
                        label={strings.percent(draft.confidence.overall)}
                        color={colors.primary}
                        accessibilityLabel={strings.receipt.confidenceA11y(
                            strings.percent(draft.confidence.overall),
                        )}
                    />
                    <View style={styles.flex}>
                        {rows.map((row) => (
                            <View
                                key={row.label}
                                accessible
                                accessibilityLabel={`${row.label}, ${strings.percent(row.value)}`}
                                style={styles.confidenceRow}
                            >
                                <Dot color={colors.primary} />
                                <Text variant="caption" tone="secondary" style={styles.flex}>
                                    {row.label}
                                </Text>
                                <Text variant="caption" weight="medium" tabular>
                                    {strings.percent(row.value)}
                                </Text>
                            </View>
                        ))}
                    </View>
                </View>
            </Card>
            <Text weight="semibold">
                {strings.receipt.linesTitle}{" "}
                <Text variant="caption">{strings.receipt.linesOptional}</Text>
            </Text>
            <Pressable
                accessibilityRole="button"
                accessibilityLabel={strings.receipt.linesDetected(draft.lineCount)}
                onPress={onOpenLines}
                style={[styles.lines, { backgroundColor: colors.card }]}
            >
                <Icon name="receipt" />
                <Text tone="secondary" style={styles.flex}>
                    {strings.receipt.linesDetected(draft.lineCount)}
                </Text>
                <Icon name="chevron" />
            </Pressable>
            <Button size="lg" label={strings.receipt.create} onPress={onConfirm} />
        </Screen>
    );
}

const styles = StyleSheet.create({
    flex: { flex: 1 },
    photoRow: { flexDirection: "row", alignItems: "center", gap: spacing.lg },
    photo: {
        width: size.thumbnail,
        height: size.thumbnail,
        borderRadius: radius.md,
        alignItems: "center",
        justifyContent: "center",
        overflow: "hidden",
    },
    field: { gap: spacing.sm },
    box: {
        minHeight: Math.max(size.field, minTouchTarget),
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.md,
        borderWidth: borderWidth.hairline,
        borderRadius: radius.md,
        paddingHorizontal: spacing.md,
    },
    amountBox: { alignItems: "stretch", paddingHorizontal: spacing.none, gap: spacing.none },
    amount: { alignSelf: "center", paddingHorizontal: spacing.lg, fontVariant: tabularFigures },
    suffix: {
        minWidth: size.field,
        alignItems: "center",
        justifyContent: "center",
        borderLeftWidth: borderWidth.hairline,
    },
    confidence: { gap: spacing.md },
    confidenceBody: { flexDirection: "row", alignItems: "center", gap: spacing.xl },
    confidenceRow: {
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.sm,
        paddingVertical: spacing.xs,
    },
    lines: {
        minHeight: Math.max(size.controlLg, minTouchTarget),
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.md,
        borderRadius: radius.lg,
        paddingHorizontal: spacing.lg,
    },
});
