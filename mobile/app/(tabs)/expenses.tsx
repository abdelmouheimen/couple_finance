import { Placeholder } from "@/shared/ui";
import { fabContentInset } from "@/shared/ui/theme/tokens";
import { strings } from "@/shared/i18n/strings";

export default function Expenses() {
    return (
        <Placeholder
            title={strings.tabs.expenses}
            message={strings.placeholders.expenses}
            bottomInset={fabContentInset}
        />
    );
}
