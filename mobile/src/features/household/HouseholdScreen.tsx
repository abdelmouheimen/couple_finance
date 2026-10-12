import { useRouter } from "expo-router";
import { strings } from "@/shared/i18n/strings";
import { Card, ListRow, Screen } from "@/shared/ui";
import { useHouseholdContext } from "./HouseholdProvider";
import { InvitationsSection } from "./InvitationsSection";

/** "Foyer" (from Settings): the household's identity and the partner invitations. */
export function HouseholdScreen() {
    const router = useRouter();
    const { household, readOnly } = useHouseholdContext();
    return (
        <Screen
            edges={["top", "left", "right", "bottom"]}
            title={strings.settings.household}
            onBack={() => router.back()}
        >
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
        </Screen>
    );
}
