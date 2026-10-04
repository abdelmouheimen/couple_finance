import { Redirect } from "expo-router";
import { useState } from "react";
import {
    Button,
    Card,
    ConfirmSheet,
    EmptyState,
    ErrorState,
    IconButton,
    ListRow,
    MoneyInput,
    MoneyText,
    ProgressBar,
    Screen,
    Selector,
    Skeleton,
    Text,
    TextInput,
    useToast,
} from "@/shared/ui";

type Scope = "HOUSEHOLD" | "PERSONAL";

/** Internal component gallery. Dev builds only: production builds redirect away. */
export default function Gallery() {
    const toast = useToast();
    const [amount, setAmount] = useState("");
    const [scope, setScope] = useState<Scope | null>("HOUSEHOLD");
    const [confirm, setConfirm] = useState(false);

    if (!__DEV__) return <Redirect href="/" />;

    return (
        <Screen>
            <Text variant="headline" accessibilityRole="header">
                Gallery
            </Text>
            <Card>
                <Button label="Primary" onPress={() => toast("Saved")} />
                <Button label="Secondary" variant="secondary" onPress={() => undefined} />
                <Button
                    label="Destructive"
                    variant="destructive"
                    onPress={() => setConfirm(true)}
                />
                <Button label="Loading" loading onPress={() => undefined} />
                <IconButton icon="add" label="Add" onPress={() => undefined} />
                <Button label="Show error toast" onPress={() => toast("Failed", "error")} />
            </Card>
            <Card>
                <TextInput label="Merchant" error="Required" />
                <MoneyInput
                    label="Amount"
                    value={amount}
                    onChangeValue={setAmount}
                    currency="EUR"
                />
                <Selector
                    label="Scope"
                    value={scope}
                    onChange={setScope}
                    options={[
                        { value: "HOUSEHOLD", label: "Household" },
                        { value: "PERSONAL", label: "Personal" },
                    ]}
                />
            </Card>
            <Card>
                <MoneyText money={{ amount: "1234.50", currency: "EUR" }} scope="HOUSEHOLD" />
                <ProgressBar percent={45} status="ON_TRACK" />
                <ProgressBar percent={90} status="WARNING" />
                <ProgressBar percent={120} status="EXCEEDED" />
                <ListRow title="Groceries" subtitle="Today" onPress={() => undefined} />
            </Card>
            <Card>
                <Skeleton variant="number" />
                <Skeleton variant="row" />
                <Skeleton variant="card" />
            </Card>
            <EmptyState
                title="Nothing yet"
                message="Add your first item."
                actionLabel="Add"
                onAction={() => undefined}
            />
            <ErrorState onRetry={() => toast("Retrying")} />
            <ConfirmSheet
                visible={confirm}
                title="Delete expense?"
                message="This cannot be undone."
                confirmLabel="Delete"
                onConfirm={() => setConfirm(false)}
                onCancel={() => setConfirm(false)}
            />
        </Screen>
    );
}
