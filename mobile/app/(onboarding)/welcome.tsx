import { Placeholder } from "@/shared/ui";
import { strings } from "@/shared/i18n/strings";

export default function Welcome() {
    return <Placeholder title={strings.appName} message={strings.placeholders.onboarding} />;
}
