import { View } from "react-native";
import Svg, { Circle, G } from "react-native-svg";
import { donutSize, donutThickness } from "@/shared/ui/theme/tokens";

export interface DonutArc {
    key: string;
    tenths: number;
    color: string;
}

interface Props {
    arcs: readonly DonutArc[];
    /** Colour of the unfilled track (only visible when the arcs do not make a full circle). */
    trackColor: string;
    /** Text alternative of the whole chart (the svg itself is hidden from assistive tech). */
    accessibilityLabel: string;
    testID?: string;
}

/**
 * Donut drawn with stroked circles (one dash segment per slice). Geometry comes from integer tenths of
 * server percentages; the chart carries no amounts.
 */
export function DonutChart({ arcs, trackColor, accessibilityLabel, testID }: Props) {
    const center = donutSize / 2;
    const radius = (donutSize - donutThickness) / 2;
    const circumference = 2 * Math.PI * radius;
    const total = arcs.reduce((sum, a) => sum + a.tenths, 0);
    let consumed = 0;
    return (
        <View
            testID={testID}
            accessible
            accessibilityRole="image"
            accessibilityLabel={accessibilityLabel}
            style={{ width: donutSize, height: donutSize }}
        >
            <Svg width={donutSize} height={donutSize} viewBox={`0 0 ${donutSize} ${donutSize}`}>
                <G rotation={-90} origin={`${center}, ${center}`}>
                    <Circle
                        cx={center}
                        cy={center}
                        r={radius}
                        stroke={trackColor}
                        strokeWidth={donutThickness}
                        fill="none"
                    />
                    {total > 0
                        ? arcs.map((arc) => {
                              const length = (circumference * arc.tenths) / total;
                              const offset = (circumference * consumed) / total;
                              consumed += arc.tenths;
                              return (
                                  <Circle
                                      key={arc.key}
                                      testID="donut-arc"
                                      cx={center}
                                      cy={center}
                                      r={radius}
                                      stroke={arc.color}
                                      strokeWidth={donutThickness}
                                      fill="none"
                                      strokeDasharray={`${length} ${circumference - length}`}
                                      strokeDashoffset={-offset}
                                  />
                              );
                          })
                        : null}
                </G>
            </Svg>
        </View>
    );
}
