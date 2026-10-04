import { Placeholder } from "@/shared/ui";
import { fabContentInset } from "@/shared/ui/theme/tokens";
import { strings } from "@/shared/i18n/strings";

export default function Home() {
    return (
        <Placeholder
            title={strings.tabs.home}
            message={strings.placeholders.home}
            bottomInset={fabContentInset}
        />
    );
}
