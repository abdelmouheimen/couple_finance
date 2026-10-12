import { useLocalSearchParams } from "expo-router";
import { ReceiptDetailScreen } from "@/features/receipt/ReceiptDetailScreen";

export default function ReceiptDetail() {
    const { id } = useLocalSearchParams<{ id: string }>();
    return <ReceiptDetailScreen id={id} />;
}
