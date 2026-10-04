import { useLocalSearchParams } from "expo-router";
import { ExpenseDetailScreen } from "@/features/expense/ExpenseDetailScreen";

export default function ExpenseDetail() {
    const { id } = useLocalSearchParams<{ id: string }>();
    return <ExpenseDetailScreen id={id} />;
}
