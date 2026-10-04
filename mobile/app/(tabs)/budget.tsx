import { Placeholder } from "@/shared/ui";
import { fabContentInset } from "@/shared/ui/theme/tokens";
import { strings } from "@/shared/i18n/strings";

export default function Budget() {
    return (
        <Placeholder
            title={strings.tabs.budget}
            message={strings.placeholders.budget}
            bottomInset={fabContentInset}
        />
    );
}
