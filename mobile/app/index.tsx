import { Text, View } from "react-native";

/** Placeholder root screen: fetches nothing. Real screens arrive with MOBILE-002+. */
export default function Home() {
    return (
        <View style={{ flex: 1, alignItems: "center", justifyContent: "center" }}>
            <Text accessibilityRole="header">CoupleFinance</Text>
        </View>
    );
}
