import { Text as RNText } from "react-native";
import { useColors } from "./theme/theme";
import { type IconName, iconSize, icons } from "./theme/tokens";

/** Decorative glyph: hidden from assistive tech; meaning is carried by a sibling label. */
export function Icon({
    name,
    size = "md",
    color,
}: {
    name: IconName;
    size?: keyof typeof iconSize;
    color?: string;
}) {
    const colors = useColors();
    return (
        <RNText
            accessible={false}
            importantForAccessibility="no-hide-descendants"
            accessibilityElementsHidden
            allowFontScaling={false}
            style={{
                color: color ?? colors.text,
                fontSize: iconSize[size],
                lineHeight: iconSize[size] + 4,
            }}
        >
            {icons[name]}
        </RNText>
    );
}
