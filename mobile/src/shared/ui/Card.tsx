import { type ReactNode } from "react";
import { View, type ViewProps } from "react-native";
import { useColors } from "./theme/theme";
import { radius, spacing } from "./theme/tokens";

/** Flat card of the approved design: card colour, 16 dp corners, 16 dp padding, no shadow. */
export function Card({ children, style, ...rest }: ViewProps & { children: ReactNode }) {
    const colors = useColors();
    return (
        <View
            {...rest}
            style={[
                {
                    backgroundColor: colors.card,
                    borderRadius: radius.lg,
                    padding: spacing.lg,
                    gap: spacing.sm,
                },
                style,
            ]}
        >
            {children}
        </View>
    );
}
