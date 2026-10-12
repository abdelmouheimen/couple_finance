import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "expo-router";
import { Fragment, useState } from "react";
import { Pressable, RefreshControl, StyleSheet, View } from "react-native";
import { useHouseholdContext } from "@/features/household/HouseholdProvider";
import { signOut } from "@/features/identity/authApi";
import { strings } from "@/shared/i18n/strings";
import {
    Avatar,
    Button,
    Card,
    ConfirmSheet,
    Divider,
    Icon,
    ListRow,
    Screen,
    ScreenTitle,
    Text,
    useColors,
    useToast,
} from "@/shared/ui";
import { type IconName } from "@/shared/ui/theme/icons";
import { minTouchTarget, radius, size, spacing } from "@/shared/ui/theme/tokens";

interface Entry {
    key: string;
    icon: IconName;
    label: string;
    /** Absent while the destination does not exist yet: the row then only says so. */
    onPress?: () => void;
}

function Group({
    entries,
    onUnavailable,
}: {
    entries: readonly Entry[];
    onUnavailable: () => void;
}) {
    return (
        <Card style={styles.group}>
            {entries.map((entry, index) => (
                <Fragment key={entry.key}>
                    {index > 0 ? <Divider /> : null}
                    <View style={styles.row}>
                        <ListRow
                            testID={`settings-${entry.key}`}
                            icon={entry.icon}
                            title={entry.label}
                            onPress={entry.onPress ?? onUnavailable}
                            {...(entry.onPress
                                ? {}
                                : { accessibilityHint: strings.settings.comingSoonHint })}
                        />
                    </View>
                </Fragment>
            ))}
        </Card>
    );
}

/** Settings tab, as in the approved design: household card, then the two grouped lists. */
export function SettingsScreen() {
    const router = useRouter();
    const { household } = useHouseholdContext();
    const colors = useColors();
    const toast = useToast();
    const queryClient = useQueryClient();
    const [confirmingSignOut, setConfirmingSignOut] = useState(false);
    const [refreshing, setRefreshing] = useState(false);
    async function refresh() {
        setRefreshing(true);
        try {
            await queryClient.invalidateQueries({ queryKey: ["household"] });
        } finally {
            setRefreshing(false);
        }
    }
    const openHousehold = () => router.push("/household");
    const unavailable = () => toast(strings.comingSoon);

    const main: Entry[] = [
        { key: "profile", icon: "user", label: strings.settings.profile },
        {
            key: "household",
            icon: "home",
            label: strings.settings.household,
            onPress: openHousehold,
        },
        {
            key: "categories",
            icon: "categories",
            label: strings.settings.categories,
            onPress: () => router.push("/categories"),
        },
        {
            key: "budget",
            icon: "budget",
            label: strings.settings.budget,
            onPress: () => router.push("/budget"),
        },
        { key: "rules", icon: "rules", label: strings.settings.rules },
        { key: "notifications", icon: "bell", label: strings.settings.notifications },
        { key: "security", icon: "shield", label: strings.settings.security },
        { key: "privacy", icon: "lock", label: strings.settings.privacy },
    ];
    const about: Entry[] = [
        { key: "help", icon: "help", label: strings.settings.help },
        { key: "about", icon: "info", label: strings.settings.about },
    ];

    return (
        <Screen
            refreshControl={
                <RefreshControl
                    refreshing={refreshing}
                    onRefresh={() => void refresh()}
                    tintColor={colors.primary}
                />
            }
        >
            <ScreenTitle>{strings.household.settingsTitle}</ScreenTitle>
            <Pressable
                testID="settings-household-card"
                accessibilityRole="button"
                accessibilityLabel={`${household.name}, ${strings.household.infoLine(
                    household.currency,
                    household.timezone,
                    household.periodStartDay,
                )}`}
                onPress={openHousehold}
                style={[styles.household, { backgroundColor: colors.card }]}
            >
                <Avatar large />
                <View style={styles.flex}>
                    <Text weight="semibold" numberOfLines={1}>
                        {household.name}
                    </Text>
                    <Text variant="caption" tone="secondary" numberOfLines={1}>
                        {strings.household.infoLine(
                            household.currency,
                            household.timezone,
                            household.periodStartDay,
                        )}
                    </Text>
                </View>
                <Icon name="chevron" color={colors.textSecondary} />
            </Pressable>
            <Group entries={main} onUnavailable={unavailable} />
            <Group entries={about} onUnavailable={unavailable} />
            <Button
                variant="secondary"
                icon="signOut"
                label={strings.auth.signOut}
                onPress={() => setConfirmingSignOut(true)}
            />
            <ConfirmSheet
                visible={confirmingSignOut}
                title={strings.auth.signOutTitle}
                message={strings.auth.signOutMessage}
                confirmLabel={strings.auth.signOut}
                onConfirm={() => {
                    setConfirmingSignOut(false);
                    void signOut();
                }}
                onCancel={() => setConfirmingSignOut(false)}
            />
        </Screen>
    );
}

const styles = StyleSheet.create({
    flex: { flex: 1 },
    household: {
        minHeight: minTouchTarget,
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.lg,
        padding: spacing.lg,
        borderRadius: radius.lg,
    },
    group: { gap: spacing.none, paddingVertical: spacing.none },
    row: { minHeight: size.field, justifyContent: "center" },
});
