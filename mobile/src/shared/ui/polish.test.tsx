import { act, fireEvent, screen } from "@testing-library/react-native";
import { readdirSync, readFileSync, statSync } from "fs";
import { join } from "path";
import { AccessibilityInfo, AppState, StyleSheet } from "react-native";
import { Button } from "./Button";
import { PrivacyShield } from "./PrivacyShield";
import { renderUi } from "./testing";
import { Text } from "./Text";
import { useToast } from "./Toast";

describe("Button double-submit protection (MOBILE-007)", () => {
    test("a second press while the async action is pending is ignored", async () => {
        let resolve: () => void = () => {};
        const onPress = jest.fn(
            () =>
                new Promise<void>((r) => {
                    resolve = r;
                }),
        );
        await renderUi(<Button label="Save" onPress={onPress} />);
        const button = screen.getByRole("button", { name: "Save" });
        await fireEvent.press(button);
        await fireEvent.press(button);
        expect(onPress).toHaveBeenCalledTimes(1);
        await act(async () => {
            resolve();
        });
        await fireEvent.press(button);
        expect(onPress).toHaveBeenCalledTimes(2);
    });

    test("a rejected action releases the guard", async () => {
        const onPress = jest.fn(() => Promise.reject(new Error("x")));
        await renderUi(<Button label="Save" onPress={onPress} />);
        const button = screen.getByRole("button", { name: "Save" });
        await fireEvent.press(button);
        await act(async () => {});
        await fireEvent.press(button);
        expect(onPress).toHaveBeenCalledTimes(2);
    });
});

describe("PrivacyShield (security.md §9)", () => {
    test("covers the content when the app leaves the foreground", async () => {
        let listener: (s: string) => void = () => {};
        const remove = jest.fn();
        jest.spyOn(AppState, "addEventListener").mockImplementation(((
            _: string,
            cb: (s: string) => void,
        ) => {
            listener = cb;
            return { remove };
        }) as never);
        await renderUi(<PrivacyShield />);
        await act(async () => listener("active"));
        expect(screen.queryByTestId("privacy-shield", { includeHiddenElements: true })).toBeNull();
        await act(async () => listener("inactive"));
        expect(screen.getByTestId("privacy-shield", { includeHiddenElements: true })).toBeTruthy();
        expect(screen.getByText("CoupleFinance", { includeHiddenElements: true })).toBeTruthy();
        await act(async () => listener("active"));
        expect(screen.queryByTestId("privacy-shield", { includeHiddenElements: true })).toBeNull();
        jest.restoreAllMocks();
    });
});

describe("no debug logging in shipped code (MOBILE-007)", () => {
    const root = join(__dirname, "..", "..", "..");
    function files(dir: string): string[] {
        return readdirSync(dir).flatMap((name) => {
            const p = join(dir, name);
            if (statSync(p).isDirectory()) return name === "generated" ? [] : files(p);
            return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [p] : [];
        });
    }
    test("no console.* calls in app/ or src/", () => {
        const offenders = [join(root, "app"), join(root, "src")]
            .flatMap(files)
            .filter((f) => /\bconsole\.\w+/.test(readFileSync(f, "utf8")));
        expect(offenders).toEqual([]);
    });
});

describe("touch targets of raw Pressables", () => {
    const root = join(__dirname, "..", "..", "..");
    test("every Pressable declares a minimum touch target", () => {
        const targets = [
            "app/(tabs)/_layout.tsx",
            "src/features/expense/ExpenseRow.tsx",
            "src/features/identity/PasswordField.tsx",
            "src/shared/ui/Chips.tsx",
            "src/shared/ui/ListRow.tsx",
            "src/shared/ui/Button.tsx",
            "src/shared/ui/IconButton.tsx",
        ];
        for (const t of targets) {
            expect({
                t,
                ok: /minTouchTarget|fabSize/.test(readFileSync(join(root, t), "utf8")),
            }).toEqual({
                t,
                ok: true,
            });
        }
    });
});

describe("component accessibility contracts (MOBILE-007)", () => {
    test("Button enforces the minimum touch target and shows busy while pending", async () => {
        let resolve: () => void = () => {};
        await renderUi(
            <Button
                label="Save"
                onPress={() =>
                    new Promise<void>((r) => {
                        resolve = r;
                    })
                }
            />,
        );
        const button = screen.getByRole("button", { name: "Save" });
        const style = StyleSheet.flatten(
            typeof button.props.style === "function"
                ? button.props.style({ pressed: false })
                : button.props.style,
        );
        expect(style.minHeight).toBeGreaterThanOrEqual(44);
        await fireEvent.press(button);
        expect(screen.getByRole("button", { name: "Save" }).props.accessibilityState.busy).toBe(
            true,
        );
        await act(async () => {
            resolve();
        });
        expect(screen.getByRole("button", { name: "Save" }).props.accessibilityState.busy).toBe(
            false,
        );
    });

    test("Text caps font scaling so amounts are not clipped at 200 %", async () => {
        await renderUi(<Text variant="amount">12.50</Text>);
        expect(screen.getByText("12.50").props.maxFontSizeMultiplier).toBe(2);
    });

    test("a toast is announced to screen readers", async () => {
        const announce = jest.spyOn(AccessibilityInfo, "announceForAccessibility");
        function Trigger() {
            const toast = useToast();
            return <Button label="Go" onPress={() => toast("Saved", "success")} />;
        }
        await renderUi(<Trigger />);
        await fireEvent.press(screen.getByRole("button", { name: "Go" }));
        expect(announce).toHaveBeenCalledWith("Saved");
        jest.restoreAllMocks();
    });
});
