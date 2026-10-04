import { type ReactNode } from "react";
import { View, type ViewProps } from "react-native";
import { useColors } from "./theme/theme";
import { elevation, radius, spacing } from "./theme/tokens";

export function Card({ children, style, ...rest }: ViewProps & { children: ReactNode }) {
    const colors = useColors();
    return (
        <View
            {...rest}
            style={[
                {
                    backgroundColor: colors.surface,
                    borderRadius: radius.lg,
                    padding: spacing.lg,
                    gap: spacing.sm,
                },
                elevation.card,
                style,
            ]}
        >
            {children}
        </View>
    );
}
