import { EmptyState } from "./States";
import { Screen } from "./Screen";

/** Empty route placeholder until the owning feature Issue lands. */
export function Placeholder({ title, message }: { title: string; message: string }) {
    return (
        <Screen>
            <EmptyState title={title} message={message} />
        </Screen>
    );
}
