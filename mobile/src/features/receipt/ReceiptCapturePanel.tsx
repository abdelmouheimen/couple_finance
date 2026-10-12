import { Pressable, StyleSheet, View } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { Button, Icon, Text, useColors } from "@/shared/ui";
import { type IconName } from "@/shared/ui/theme/icons";
import {
    borderWidth,
    minTouchTarget,
    opacity,
    radius,
    size,
    spacing,
    viewfinderAspectRatio,
    viewfinderCorner,
} from "@/shared/ui/theme/tokens";

/**
 * Receipt capture and AI extraction (features F4/F5) have no backend yet: the capture screens exist as
 * design only. While this is `false` nothing is captured, uploaded or analysed, every capture control
 * is disabled and the screen says so; it never pretends to work.
 */
export const RECEIPT_CAPTURE_AVAILABLE = false;

type Mode = "SCAN" | "IMPORT";

function Corner({ position }: { position: "tl" | "tr" | "bl" | "br" }) {
    const colors = useColors();
    const top = position === "tl" || position === "tr";
    const left = position === "tl" || position === "bl";
    return (
        <View
            style={[
                styles.corner,
                { borderColor: colors.primary },
                top
                    ? { top: spacing.xl, borderTopWidth: borderWidth.thick }
                    : { bottom: spacing.xl, borderBottomWidth: borderWidth.thick },
                left
                    ? { left: spacing.xl, borderLeftWidth: borderWidth.thick }
                    : { right: spacing.xl, borderRightWidth: borderWidth.thick },
                top && left ? { borderTopLeftRadius: radius.sm } : null,
                top && !left ? { borderTopRightRadius: radius.sm } : null,
                !top && left ? { borderBottomLeftRadius: radius.sm } : null,
                !top && !left ? { borderBottomRightRadius: radius.sm } : null,
            ]}
        />
    );
}

function RoundControl({ icon, label }: { icon: IconName; label: string }) {
    const colors = useColors();
    return (
        <Pressable
            accessibilityRole="button"
            accessibilityLabel={label}
            accessibilityState={{ disabled: !RECEIPT_CAPTURE_AVAILABLE }}
            disabled={!RECEIPT_CAPTURE_AVAILABLE}
            style={[
                styles.round,
                { backgroundColor: colors.card },
                RECEIPT_CAPTURE_AVAILABLE ? null : styles.disabled,
            ]}
        >
            <Icon name={icon} size="lg" />
        </Pressable>
    );
}

/**
 * "Scanner" / "Importer" panel of the add-expense screen, laid out as in the approved design:
 * viewfinder with corner brackets, then gallery / shutter / flash, then the helper text.
 */
export function ReceiptCapturePanel({
    mode,
    onUseManual,
}: {
    mode: Mode;
    onUseManual: () => void;
}) {
    const colors = useColors();
    const title = mode === "SCAN" ? strings.receipt.scanTitle : strings.receipt.importTitle;
    return (
        <View style={styles.panel} testID={`receipt-panel-${mode}`}>
            <View
                accessible
                accessibilityRole="image"
                accessibilityLabel={strings.receipt.viewfinder}
                style={[styles.viewfinder, { backgroundColor: colors.card }]}
            >
                <Corner position="tl" />
                <Corner position="tr" />
                <Corner position="bl" />
                <Corner position="br" />
                <Icon
                    name={mode === "SCAN" ? "camera" : "upload"}
                    size="lg"
                    color={colors.textSecondary}
                />
            </View>
            <View style={styles.controls}>
                <RoundControl icon="image" label={strings.receipt.gallery} />
                <Pressable
                    testID="receipt-shutter"
                    accessibilityRole="button"
                    accessibilityLabel={strings.receipt.capture}
                    accessibilityState={{ disabled: !RECEIPT_CAPTURE_AVAILABLE }}
                    disabled={!RECEIPT_CAPTURE_AVAILABLE}
                    style={[
                        styles.shutter,
                        { borderColor: colors.text },
                        RECEIPT_CAPTURE_AVAILABLE ? null : styles.disabled,
                    ]}
                >
                    <View style={[styles.shutterCore, { backgroundColor: colors.text }]} />
                </Pressable>
                <RoundControl icon="flash" label={strings.receipt.flash} />
            </View>
            {RECEIPT_CAPTURE_AVAILABLE ? (
                <View style={styles.help}>
                    <Text weight="medium" style={styles.centered}>
                        {title}
                    </Text>
                    <Text variant="caption" tone="secondary" style={styles.centered}>
                        {strings.receipt.scanMessage}
                    </Text>
                </View>
            ) : (
                <View style={styles.help} accessibilityLiveRegion="polite">
                    <Text weight="medium" style={styles.centered}>
                        {strings.receipt.unavailableTitle}
                    </Text>
                    <Text variant="caption" tone="secondary" style={styles.centered}>
                        {strings.receipt.unavailableMessage}
                    </Text>
                    <Button
                        variant="secondary"
                        label={strings.receipt.useManual}
                        onPress={onUseManual}
                    />
                </View>
            )}
        </View>
    );
}

const styles = StyleSheet.create({
    panel: { gap: spacing.xl },
    viewfinder: {
        alignSelf: "stretch",
        aspectRatio: viewfinderAspectRatio,
        maxHeight: size.thumbnail * 5,
        borderRadius: radius.lg,
        alignItems: "center",
        justifyContent: "center",
        overflow: "hidden",
    },
    corner: { position: "absolute", width: viewfinderCorner, height: viewfinderCorner },
    controls: {
        flexDirection: "row",
        alignItems: "center",
        justifyContent: "space-around",
    },
    round: {
        width: Math.max(size.controlLg, minTouchTarget),
        height: Math.max(size.controlLg, minTouchTarget),
        borderRadius: radius.pill,
        alignItems: "center",
        justifyContent: "center",
    },
    shutter: {
        width: size.shutter,
        height: size.shutter,
        borderRadius: radius.pill,
        borderWidth: borderWidth.thick,
        padding: spacing.xs,
    },
    shutterCore: { flex: 1, borderRadius: radius.pill },
    disabled: { opacity: opacity.disabled },
    help: { alignItems: "center", gap: spacing.sm },
    centered: { textAlign: "center" },
});
