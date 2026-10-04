import { useHouseholdContext } from "@/features/household/HouseholdProvider";
import { InvitationsSection } from "@/features/household/InvitationsSection";
import { signOut } from "@/features/identity/authApi";
import { strings } from "@/shared/i18n/strings";
import { Button, Card, ListRow, Screen, Text } from "@/shared/ui";
import { fabContentInset } from "@/shared/ui/theme/tokens";

export default function Settings() {
    const { household, readOnly } = useHouseholdContext();
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
            </Card>
            <InvitationsSection readOnly={readOnly} />
            <Button
                variant="secondary"
                label={strings.auth.signOut}
                onPress={() => void signOut()}
            />
        </Screen>
    );
}
