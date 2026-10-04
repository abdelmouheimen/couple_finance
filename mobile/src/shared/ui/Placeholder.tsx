import { EmptyState } from "./States";
import { Screen } from "./Screen";

interface Props {
    title: string;
    message: string;
    /** Modal screens pass these so the placeholder has a visible exit and safe-area handling. */
    onClose?: () => void;
    withBottomEdge?: boolean;
    bottomInset?: number;
}

/** Empty route placeholder until the owning feature Issue lands. */
export function Placeholder({ title, message, onClose, withBottomEdge, bottomInset }: Props) {
    return (
        <Screen
            onClose={onClose}
            bottomInset={bottomInset}
            edges={withBottomEdge ? ["top", "left", "right", "bottom"] : undefined}
        >
            <EmptyState title={title} message={message} />
        </Screen>
    );
}
