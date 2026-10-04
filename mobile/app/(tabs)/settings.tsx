import { useRouter } from "expo-router";
import { useState } from "react";
import { useHouseholdContext } from "@/features/household/HouseholdProvider";
import { InvitationsSection } from "@/features/household/InvitationsSection";
import { signOut } from "@/features/identity/authApi";
import { strings } from "@/shared/i18n/strings";
import { Button, Card, ConfirmSheet, ListRow, Screen, Text } from "@/shared/ui";
import { fabContentInset } from "@/shared/ui/theme/tokens";

export default function Settings() {
    const router = useRouter();
    const { household, readOnly } = useHouseholdContext();
    const [confirmingSignOut, setConfirmingSignOut] = useState(false);
    return (
        <Screen bottomInset={fabContentInset}>
            <Text variant="headline" accessibilityRole="header">
                {strings.household.settingsTitle}
            </Text>
            <Card>
                <ListRow
                    title={household.name}
                    subtitle={strings.household.infoLine(
                        household.currency,
                        household.timezone,
                        household.periodStartDay,
                    )}
                />
                <ListRow
                    title={strings.category.manage}
                    onPress={() => router.push("/categories")}
                />
            </Card>
            <InvitationsSection readOnly={readOnly} />
            <Button
                variant="secondary"
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
