import { useQueryClient } from "@tanstack/react-query";
import { CreateHouseholdForm } from "@/features/household/CreateHouseholdForm";
import { HOUSEHOLD_QUERY_KEY } from "@/features/household/HouseholdProvider";
import { createHousehold } from "@/features/household/householdApi";
import { strings } from "@/shared/i18n/strings";
import { Screen, Text } from "@/shared/ui";

export default function CreateHousehold() {
    const queryClient = useQueryClient();
    return (
        <Screen>
            <Text variant="headline" accessibilityRole="header">
                {strings.household.createTitle}
            </Text>
            <Text tone="secondary">{strings.household.createMessage}</Text>
            <CreateHouseholdForm
                onSubmit={async (input) => {
                    const household = await createHousehold(input);
                    // Seeds the household context; the gate then routes to the app.
                    queryClient.setQueryData(HOUSEHOLD_QUERY_KEY, household);
                }}
            />
        </Screen>
    );
}
