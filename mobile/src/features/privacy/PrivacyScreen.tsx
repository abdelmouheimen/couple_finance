import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "expo-router";
import { useState } from "react";
import { StyleSheet, View } from "react-native";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import {
    Button,
    Card,
    ErrorState,
    Screen,
    SectionTitle,
    Skeleton,
    Text,
    useToast,
} from "@/shared/ui";
import { spacing } from "@/shared/ui/theme/tokens";
import {
    AI_CONSENTS_KEY,
    decideAiConsent,
    isGranted,
    listAiConsents,
    RECEIPT_ANALYSIS_CONSENT_VERSION,
} from "./aiConsentApi";

/**
 * Settings › Données & confidentialité (DOC-003, BR-AI-05): the receipt-analysis disclosure and its explicit,
 * withdrawable opt-in. Nothing is sent to the AI provider without this consent; withdrawing is one tap.
 */
export function PrivacyScreen() {
    const router = useRouter();
    const toast = useToast();
    const queryClient = useQueryClient();
    const [error, setError] = useState<string | null>(null);
    const consents = useQuery({ queryKey: AI_CONSENTS_KEY, queryFn: listAiConsents, retry: false });
    const back = () => router.back();
    const text = strings.privacy.receiptAnalysis;

    if (consents.isPending) {
        return (
            <Screen
                edges={["top", "left", "right", "bottom"]}
                title={strings.settings.privacy}
                onBack={back}
            >
                <Skeleton variant="card" />
            </Screen>
        );
    }
    if (consents.isError) {
        return (
            <Screen
                edges={["top", "left", "right", "bottom"]}
                title={strings.settings.privacy}
                onBack={back}
            >
                <ErrorState
                    message={errorMessage(consents.error)}
                    onRetry={() => void consents.refetch()}
                />
            </Screen>
        );
    }

    const granted = isGranted(consents.data, "RECEIPT_ANALYSIS");

    const decide = async (next: boolean) => {
        setError(null);
        try {
            await decideAiConsent("RECEIPT_ANALYSIS", next, RECEIPT_ANALYSIS_CONSENT_VERSION);
            await queryClient.invalidateQueries({ queryKey: AI_CONSENTS_KEY });
            toast(next ? text.grantedToast : text.withdrawnToast, "success");
        } catch (e) {
            setError(errorMessage(e));
        }
    };

    return (
        <Screen
            edges={["top", "left", "right", "bottom"]}
            title={strings.settings.privacy}
            onBack={back}
        >
            <SectionTitle>{text.title}</SectionTitle>
            <Card>
                <View style={styles.header}>
                    <Text weight="semibold" style={styles.flex}>
                        {text.statusLabel}
                    </Text>
                    <Text
                        testID="receipt-analysis-consent-status"
                        variant="caption"
                        weight="semibold"
                        tone={granted ? "primary" : "secondary"}
                    >
                        {granted ? text.statusGranted : text.statusNotGranted}
                    </Text>
                </View>
                {text.disclosure.map((paragraph) => (
                    <Text key={paragraph} variant="caption" tone="secondary">
                        {paragraph}
                    </Text>
                ))}
            </Card>
            {error ? (
                <Text tone="danger" accessibilityLiveRegion="polite" testID="privacy-error">
                    {error}
                </Text>
            ) : null}
            {granted ? (
                <Button variant="secondary" label={text.withdraw} onPress={() => decide(false)} />
            ) : (
                <Button label={text.grant} onPress={() => decide(true)} />
            )}
        </Screen>
    );
}

const styles = StyleSheet.create({
    flex: { flex: 1 },
    header: { flexDirection: "row", alignItems: "center", gap: spacing.md },
});
