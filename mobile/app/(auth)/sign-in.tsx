import { Placeholder } from "@/shared/ui";
import { strings } from "@/shared/i18n/strings";

export default function SignIn() {
    return <Placeholder title={strings.appName} message={strings.placeholders.auth} />;
}
