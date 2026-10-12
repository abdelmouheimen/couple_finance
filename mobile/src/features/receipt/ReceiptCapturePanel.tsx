import { useEffect, useRef, useState } from "react";
import { Image, Pressable, StyleSheet, View } from "react-native";
import { generateIdempotencyKey } from "@/features/expense/expenseRules";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { Button, Icon, IconButton, Text, useColors } from "@/shared/ui";
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
import { type LocalReceiptFile, type Receipt, uploadReceipt } from "./receiptApi";
import {
    captureFromCamera,
    discard,
    MAX_RECEIPT_PHOTOS,
    type PickOutcome,
    pickFromGallery,
    pickPdf,
} from "./receiptDevice";

/**
 * Creating an expense from an analysed receipt (review and confirmation, DOC-004) is not available yet: uploads are
 * stored in the vault, analysed when the user consented (DOC-003), and the expense is still entered manually.
 * Nothing is presented as a confirmed amount.
 */
export const RECEIPT_ANALYSIS_AVAILABLE = false;

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

function RoundControl({
    icon,
    label,
    hint,
    onPress,
    disabled,
}: {
    icon: IconName;
    label: string;
    hint?: string;
    onPress?: () => void;
    disabled?: boolean;
}) {
    const colors = useColors();
    return (
        <Pressable
            accessibilityRole="button"
            accessibilityLabel={label}
            accessibilityHint={hint}
            accessibilityState={{ disabled: !!disabled }}
            disabled={disabled}
            onPress={onPress}
            style={[
                styles.round,
                { backgroundColor: colors.card },
                disabled ? styles.disabled : null,
            ]}
        >
            <Icon name={icon} size="lg" />
        </Pressable>
    );
}

/**
 * "Scanner" / "Importer" panel of the add-expense screen (approved design): viewfinder with corner brackets showing
 * the captured pages, gallery / shutter / flash, helper text, then the upload. Photos are converted to JPEG on the
 * device; local copies are deleted after upload or when the screen is left. A retry of the same selection reuses
 * its Idempotency-Key, so a network failure never creates a duplicate receipt.
 */
export function ReceiptCapturePanel({
    mode,
    onUploaded,
    onUseManual,
}: {
    mode: Mode;
    onUploaded: (receipt: Receipt) => void;
    onUseManual: () => void;
}) {
    const colors = useColors();
    const [files, setFiles] = useState<LocalReceiptFile[]>([]);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const key = useRef<string | null>(null);
    const latest = useRef<LocalReceiptFile[]>([]);

    useEffect(() => {
        latest.current = files;
    }, [files]);
    // Leaving the screen without uploading must not leave receipt photos in the cache.
    useEffect(() => () => discard(latest.current.map((f) => f.uri)), []);

    const isPdf = files.some((f) => f.mimeType === "application/pdf");
    const remaining = isPdf ? 0 : MAX_RECEIPT_PHOTOS - files.length;

    const replace = (next: LocalReceiptFile[]) => {
        key.current = null; // a different selection is a different request
        setError(null);
        setFiles(next);
    };

    const accept = (outcome: PickOutcome, append: boolean) => {
        if (outcome.kind === "denied") setError(strings.receipt.cameraDenied);
        if (outcome.kind !== "picked") return;
        if (!append) {
            discard(files.map((f) => f.uri));
            replace(outcome.files);
            return;
        }
        const room = MAX_RECEIPT_PHOTOS - files.length;
        discard(outcome.files.slice(room).map((f) => f.uri));
        replace([...files, ...outcome.files.slice(0, room)]);
    };

    const run = async (action: () => Promise<void>) => {
        if (busy) return;
        setBusy(true);
        try {
            await action();
        } catch (e) {
            setError(errorMessage(e));
        } finally {
            setBusy(false);
        }
    };

    const capture = () => run(async () => accept(await captureFromCamera(), !isPdf));
    const gallery = () =>
        run(async () =>
            accept(await pickFromGallery(isPdf ? MAX_RECEIPT_PHOTOS : remaining), !isPdf),
        );
    const pdf = () => run(async () => accept(await pickPdf(), false));
    const remove = (index: number) => {
        const file = files[index];
        if (file) discard([file.uri]);
        replace(files.filter((_, i) => i !== index));
    };
    const restart = () => {
        discard(files.map((f) => f.uri));
        replace([]);
    };

    const upload = () =>
        run(async () => {
            key.current ??= generateIdempotencyKey();
            const receipt = await uploadReceipt(files, key.current);
            discard(files.map((f) => f.uri));
            key.current = null;
            setFiles([]);
            onUploaded(receipt);
        });

    const title = mode === "SCAN" ? strings.receipt.scanTitle : strings.receipt.importTitle;
    const full = busy || remaining <= 0;

    return (
        <View style={styles.panel} testID={`receipt-panel-${mode}`}>
            <View
                accessible={files.length === 0}
                accessibilityRole={files.length === 0 ? "image" : undefined}
                accessibilityLabel={files.length === 0 ? strings.receipt.viewfinder : undefined}
                style={[styles.viewfinder, { backgroundColor: colors.card }]}
            >
                <Corner position="tl" />
                <Corner position="tr" />
                <Corner position="bl" />
                <Corner position="br" />
                {files.length === 0 ? (
                    <Icon
                        name={mode === "SCAN" ? "camera" : "upload"}
                        size="lg"
                        color={colors.textSecondary}
                    />
                ) : isPdf ? (
                    <View
                        style={styles.pdf}
                        accessible
                        accessibilityLabel={strings.receipt.pdfSelected}
                    >
                        <Icon name="receipt" size="lg" />
                        <Text weight="medium">{strings.receipt.pdfSelected}</Text>
                    </View>
                ) : (
                    <View style={styles.thumbs}>
                        {files.map((file, index) => (
                            <View key={file.uri} style={styles.thumb}>
                                <Image
                                    accessible
                                    accessibilityLabel={strings.receipt.selectedPage(index + 1)}
                                    source={{ uri: file.uri }}
                                    style={[styles.thumbImage, { borderColor: colors.border }]}
                                />
                                <View style={styles.thumbRemove}>
                                    <IconButton
                                        icon="close"
                                        label={strings.receipt.removePage(index + 1)}
                                        onPress={() => remove(index)}
                                        disabled={busy}
                                    />
                                </View>
                            </View>
                        ))}
                    </View>
                )}
            </View>
            {mode === "SCAN" ? (
                <View style={styles.controls}>
                    <RoundControl
                        icon="image"
                        label={strings.receipt.gallery}
                        onPress={() => void gallery()}
                        disabled={full}
                    />
                    <Pressable
                        testID="receipt-shutter"
                        accessibilityRole="button"
                        accessibilityLabel={
                            files.length === 0 ? strings.receipt.capture : strings.receipt.addPage
                        }
                        accessibilityState={{ disabled: full }}
                        disabled={full}
                        onPress={() => void capture()}
                        style={[
                            styles.shutter,
                            { borderColor: colors.text },
                            full ? styles.disabled : null,
                        ]}
                    >
                        <View style={[styles.shutterCore, { backgroundColor: colors.text }]} />
                    </Pressable>
                    <RoundControl
                        icon="flash"
                        label={strings.receipt.flash}
                        hint={strings.receipt.flashHint}
                        disabled
                    />
                </View>
            ) : (
                <View style={styles.stack}>
                    <Button
                        variant="secondary"
                        icon="receipt"
                        label={strings.receipt.pickPdf}
                        onPress={() => void pdf()}
                        disabled={busy}
                    />
                    <Button
                        variant="secondary"
                        icon="image"
                        label={strings.receipt.pickPhotos}
                        onPress={() => void gallery()}
                        disabled={full}
                    />
                </View>
            )}
            <View style={styles.help}>
                {files.length === 0 ? (
                    <>
                        <Text weight="medium" style={styles.centered}>
                            {title}
                        </Text>
                        <Text variant="caption" tone="secondary" style={styles.centered}>
                            {RECEIPT_ANALYSIS_AVAILABLE
                                ? strings.receipt.scanMessage
                                : strings.receipt.maxPages}
                        </Text>
                    </>
                ) : null}
                {error ? (
                    <Text
                        tone="danger"
                        style={styles.centered}
                        accessibilityLiveRegion="polite"
                        testID="receipt-error"
                    >
                        {error}
                    </Text>
                ) : null}
            </View>
            {files.length > 0 ? (
                <View style={styles.stack}>
                    <Button
                        size="lg"
                        testID="receipt-send"
                        label={
                            busy
                                ? strings.receipt.sending
                                : strings.receipt.send(isPdf ? 1 : files.length)
                        }
                        loading={busy}
                        onPress={() => void upload()}
                    />
                    <Button
                        variant="secondary"
                        label={strings.receipt.restart}
                        onPress={restart}
                        disabled={busy}
                    />
                </View>
            ) : (
                <Button
                    variant="secondary"
                    label={strings.receipt.useManual}
                    onPress={onUseManual}
                />
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
        padding: spacing.xxl,
    },
    corner: { position: "absolute", width: viewfinderCorner, height: viewfinderCorner },
    thumbs: { flexDirection: "row", flexWrap: "wrap", justifyContent: "center", gap: spacing.md },
    thumb: { width: size.thumbnail, height: size.thumbnail + spacing.xxl },
    thumbImage: { flex: 1, borderRadius: radius.md, borderWidth: borderWidth.hairline },
    thumbRemove: { position: "absolute", top: -spacing.md, right: -spacing.md },
    pdf: { alignItems: "center", gap: spacing.sm },
    controls: { flexDirection: "row", alignItems: "center", justifyContent: "space-around" },
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
    stack: { gap: spacing.sm },
    help: { alignItems: "center", gap: spacing.sm },
    centered: { textAlign: "center" },
});
