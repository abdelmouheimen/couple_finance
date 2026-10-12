import { Text as RNText, type TextProps } from "react-native";
import { useColors } from "./theme/theme";
import {
    defaultWeight,
    fontFamily,
    fontSize,
    type FontWeightName,
    lineHeight,
    maxFontScale,
    tabularFigures,
} from "./theme/tokens";

export type TextVariant = keyof typeof fontSize;

interface Props extends TextProps {
    variant?: TextVariant;
    tone?: "default" | "secondary" | "danger" | "success" | "warning" | "primary";
    /** Overrides the weight of the variant. */
    weight?: FontWeightName;
    /** Shorthand for `weight="semibold"`. */
    bold?: boolean;
    tabular?: boolean;
}

/** Themed text: the only place font sizes are applied. Scales with the OS setting up to 200 %. */
export function Text({
    variant = "body",
    tone = "default",
    weight,
    bold,
    tabular,
    style,
    ...rest
}: Props) {
    const colors = useColors();
    const color =
        tone === "default"
            ? colors.text
            : tone === "secondary"
              ? colors.textSecondary
              : colors[tone];
    const resolved = weight ?? (bold ? "semibold" : defaultWeight[variant]);
    return (
        <RNText
            maxFontSizeMultiplier={maxFontScale}
            {...rest}
            style={[
                {
                    color,
                    fontSize: fontSize[variant],
                    lineHeight: lineHeight[variant],
                    fontFamily: fontFamily[resolved],
                    ...(tabular || variant === "h1" ? { fontVariant: tabularFigures } : {}),
                },
                style,
            ]}
        />
    );
}
