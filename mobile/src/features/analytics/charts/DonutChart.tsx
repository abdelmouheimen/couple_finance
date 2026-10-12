import { type ReactNode } from "react";
import { StyleSheet, View } from "react-native";
import Svg, { Circle, G } from "react-native-svg";
import { size } from "@/shared/ui/theme/tokens";

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
    /** Centre label (server total); part of the chart for assistive tech through the label above. */
    children?: ReactNode;
    testID?: string;
}

/**
 * Donut drawn with stroked circles (one dash segment per slice). Geometry comes from integer tenths of
 * server percentages; the chart computes no amount.
 */
export function DonutChart({ arcs, trackColor, accessibilityLabel, children, testID }: Props) {
    const center = size.donut / 2;
    const radius = (size.donut - size.donutStroke) / 2;
    const circumference = 2 * Math.PI * radius;
    const total = arcs.reduce((sum, a) => sum + a.tenths, 0);
    let consumed = 0;
    return (
        <View
            testID={testID}
            accessible
            accessibilityRole="image"
            accessibilityLabel={accessibilityLabel}
            style={styles.box}
        >
            <Svg width={size.donut} height={size.donut} viewBox={`0 0 ${size.donut} ${size.donut}`}>
                <G transform={`rotate(-90 ${center} ${center})`}>
                    <Circle
                        cx={center}
                        cy={center}
                        r={radius}
                        stroke={trackColor}
                        strokeWidth={size.donutStroke}
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
                                      strokeWidth={size.donutStroke}
                                      fill="none"
                                      strokeDasharray={`${length} ${circumference - length}`}
                                      strokeDashoffset={-offset}
                                  />
                              );
                          })
                        : null}
                </G>
            </Svg>
            {children ? <View style={styles.center}>{children}</View> : null}
        </View>
    );
}

const styles = StyleSheet.create({
    box: { width: size.donut, height: size.donut },
    center: {
        position: "absolute",
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        alignItems: "center",
        justifyContent: "center",
    },
});
