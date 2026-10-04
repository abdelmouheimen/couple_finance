import { useRouter } from "expo-router";
import { Placeholder } from "@/shared/ui";
import { strings } from "@/shared/i18n/strings";

export default function AddExpense() {
    const router = useRouter();
    return (
        <Placeholder
            title={strings.addExpense}
            message={strings.placeholders.addExpense}
            onClose={() => router.back()}
            withBottomEdge
        />
    );
}
