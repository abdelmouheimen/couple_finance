import { useLocalSearchParams } from "expo-router";
import { EditExpenseScreen } from "@/features/expense/EditExpenseScreen";

export default function EditExpense() {
    const { id } = useLocalSearchParams<{ id: string }>();
    return <EditExpenseScreen id={id} />;
}
