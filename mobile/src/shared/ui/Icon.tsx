import { View } from "react-native";
import Svg, { Circle, Path, Rect } from "react-native-svg";
import { type IconName, icons } from "./theme/icons";
import { useColors } from "./theme/theme";
import { iconSize, iconStrokeWidth } from "./theme/tokens";

const GRID = 24;

/** Decorative vector icon: hidden from assistive tech; meaning is carried by a sibling label. */
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
    const stroke = color ?? colors.text;
    const side = iconSize[size];
    return (
        <View
            accessible={false}
            importantForAccessibility="no-hide-descendants"
            accessibilityElementsHidden
            testID={`icon-${name}`}
            style={{ width: side, height: side }}
        >
            <Svg width={side} height={side} viewBox={`0 0 ${GRID} ${GRID}`} fill="none">
                {icons[name].map((shape, index) => {
                    const common = {
                        stroke,
                        strokeWidth: iconStrokeWidth,
                        strokeLinecap: "round" as const,
                        strokeLinejoin: "round" as const,
                    };
                    if (shape.t === "path") return <Path key={index} d={shape.d} {...common} />;
                    if (shape.t === "circle") {
                        return (
                            <Circle
                                key={index}
                                cx={shape.cx}
                                cy={shape.cy}
                                r={shape.r}
                                fill={shape.solid ? stroke : "none"}
                                {...common}
                            />
                        );
                    }
                    return (
                        <Rect
                            key={index}
                            x={shape.x}
                            y={shape.y}
                            width={shape.w}
                            height={shape.h}
                            rx={shape.rx}
                            {...common}
                        />
                    );
                })}
            </Svg>
        </View>
    );
}
