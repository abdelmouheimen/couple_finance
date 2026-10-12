import { useLocalSearchParams } from "expo-router";
import { ReceiptReviewContainer } from "@/features/receipt/ReceiptReviewContainer";

export default function ReceiptReview() {
    const { id } = useLocalSearchParams<{ id: string }>();
    return <ReceiptReviewContainer id={id} />;
}
