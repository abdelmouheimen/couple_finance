import { Text as RNText, type TextProps } from "react-native";
import { useColors } from "./theme/theme";
import { fontSize, fontWeight, lineHeight, maxFontScale, tabularFigures } from "./theme/tokens";

export type TextVariant = "caption" | "body" | "title" | "headline" | "amount";

interface Props extends TextProps {
    variant?: TextVariant;
    tone?: "default" | "secondary" | "danger" | "success" | "warning" | "primary";
    bold?: boolean;
    tabular?: boolean;
}

/** Themed text: the only place font sizes are applied. Scales with the OS setting up to 200 %. */
export function Text({ variant = "body", tone = "default", bold, tabular, style, ...rest }: Props) {
    const colors = useColors();
    const color =
        tone === "default"
            ? colors.text
            : tone === "secondary"
              ? colors.textSecondary
              : colors[tone];
    return (
        <RNText
            maxFontSizeMultiplier={maxFontScale}
            {...rest}
            style={[
                {
                    color,
                    fontSize: fontSize[variant],
                    lineHeight: lineHeight[variant],
                    fontWeight: bold || variant !== "body" ? fontWeight.bold : fontWeight.regular,
                    ...(tabular || variant === "amount" ? { fontVariant: tabularFigures } : {}),
                },
                style,
            ]}
        />
    );
}
