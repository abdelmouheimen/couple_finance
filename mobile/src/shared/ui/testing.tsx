import { render } from "@testing-library/react-native";
import { type ReactElement } from "react";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { ThemeProvider } from "./theme/theme";
import { ToastProvider } from "./Toast";
import { type ColorScheme } from "./theme/tokens";

const metrics = {
    frame: { x: 0, y: 0, width: 390, height: 844 },
    insets: { top: 0, left: 0, right: 0, bottom: 0 },
};

/** Test helper: renders UI inside the providers the primitives expect. */
export function renderUi(ui: ReactElement, scheme: ColorScheme = "light") {
    return render(
        <SafeAreaProvider initialMetrics={metrics}>
            <ThemeProvider scheme={scheme}>
                <ToastProvider>{ui}</ToastProvider>
            </ThemeProvider>
        </SafeAreaProvider>,
    );
}
