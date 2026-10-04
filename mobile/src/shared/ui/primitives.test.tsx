import { act, fireEvent, screen } from "@testing-library/react-native";
import { useState } from "react";
import { StyleSheet } from "react-native";
import {
    Button,
    Card,
    ConfirmSheet,
    EmptyState,
    ErrorState,
    IconButton,
    ListRow,
    MoneyInput,
    MoneyText,
    ProgressBar,
    Screen,
    Selector,
    Sheet,
    Skeleton,
    Text,
    TextInput,
    useToast,
} from "./index";
import { renderUi } from "./testing";
import { minTouchTarget } from "./theme/tokens";

function minHeightOf(style: Parameters<typeof StyleSheet.flatten>[0]) {
    return (StyleSheet.flatten(style) as { minHeight?: number } | undefined)?.minHeight as number;
}

describe("Button", () => {
    test("press works and touch target is large enough", async () => {
        const onPress = jest.fn();
        await renderUi(<Button label="Save" onPress={onPress} />);
        const button = screen.getByRole("button", { name: "Save" });
        await fireEvent.press(button);
        expect(onPress).toHaveBeenCalledTimes(1);
        const style = button.props.style;
        const resolved = typeof style === "function" ? style({ pressed: false }) : style;
        expect(minHeightOf(resolved)).toBeGreaterThanOrEqual(minTouchTarget);
    });

    test("loading buttons ignore presses and expose state", async () => {
        const onPress = jest.fn();
        await renderUi(<Button label="Save" loading onPress={onPress} />);
        const button = screen.getByRole("button", { name: "Save" });
        expect(button).toBeBusy();
        expect(button).toBeDisabled();
        await fireEvent.press(button);
        expect(onPress).not.toHaveBeenCalled();
    });

    test("secondary and destructive variants render in dark mode", async () => {
        await renderUi(
            <>
                <Button label="A" variant="secondary" onPress={() => undefined} />
                <Button label="B" variant="destructive" onPress={() => undefined} />
            </>,
            "dark",
        );
        expect(screen.getByRole("button", { name: "A" })).toBeTruthy();
        expect(screen.getByRole("button", { name: "B" })).toBeTruthy();
    });
});

test("IconButton has an accessible name and a minimum touch target", async () => {
    const onPress = jest.fn();
    await renderUi(<IconButton icon="add" label="Add" onPress={onPress} />);
    const button = screen.getByRole("button", { name: "Add" });
    await fireEvent.press(button);
    expect(onPress).toHaveBeenCalled();
    expect(minHeightOf(button.props.style)).toBeGreaterThanOrEqual(minTouchTarget);
});

test("Card renders children", async () => {
    await renderUi(
        <Card>
            <Text>inside</Text>
        </Card>,
    );
    expect(screen.getByText("inside")).toBeTruthy();
});

test("TextInput is labelled and shows an inline error", async () => {
    await renderUi(<TextInput label="Merchant" error="Required" />);
    expect(screen.getByLabelText("Merchant")).toBeTruthy();
    expect(screen.getByText("Required")).toBeTruthy();
});

describe("MoneyInput", () => {
    function Harness({
        currency = "EUR",
        onValue,
    }: {
        currency?: string;
        onValue?: (v: string) => void;
    }) {
        const [value, setValue] = useState("");
        return (
            <MoneyInput
                label="Amount"
                currency={currency}
                locale="en-US"
                value={value}
                onChangeValue={(v) => {
                    setValue(v);
                    onValue?.(v);
                }}
            />
        );
    }

    test("BR_MON_01_accepts_digits_and_one_separator_and_emits_canonical_string", async () => {
        const emitted: string[] = [];
        await renderUi(<Harness onValue={(v) => emitted.push(v)} />);
        const input = screen.getByLabelText("Amount (EUR)");
        await fireEvent.changeText(input, "1a2,59x");
        expect(emitted.at(-1)).toBe("12.59");
        expect(typeof emitted.at(-1)).toBe("string");
        await fireEvent.changeText(input, "12.5");
        await fireEvent(input, "blur");
        expect(emitted.at(-1)).toBe("12.50");
        expect(screen.getByLabelText("Amount (EUR)").props.value).toBe("12.50");
    });

    test("BR_MON_03_limits_decimals_to_currency_and_shows_currency", async () => {
        const emitted: string[] = [];
        await renderUi(<Harness currency="JPY" onValue={(v) => emitted.push(v)} />);
        await fireEvent.changeText(screen.getByLabelText("Amount (JPY)"), "12.5");
        expect(emitted.at(-1)).toBe("125");
        expect(screen.getByText("JPY")).toBeTruthy();
    });

    test("trailing separator survives while typing", async () => {
        await renderUi(<Harness />);
        await fireEvent.changeText(screen.getByLabelText("Amount (EUR)"), "12.");
        expect(screen.getByLabelText("Amount (EUR)").props.value).toBe("12.");
    });

    test("shows error", async () => {
        await renderUi(
            <MoneyInput
                label="Amount"
                currency="EUR"
                value=""
                onChangeValue={() => undefined}
                error="Too high"
            />,
        );
        expect(screen.getByText("Too high")).toBeTruthy();
    });
});

test("Selector exposes radio state and changes selection", async () => {
    const onChange = jest.fn();
    await renderUi(
        <Selector
            label="Scope"
            value="A"
            onChange={onChange}
            options={[
                { value: "A", label: "Alpha" },
                { value: "B", label: "Beta" },
            ]}
        />,
    );
    expect(screen.getByRole("radio", { name: "Alpha" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "Beta" })).not.toBeChecked();
    await fireEvent.press(screen.getByRole("radio", { name: "Beta" }));
    expect(onChange).toHaveBeenCalledWith("B");
});

describe("ProgressBar", () => {
    test.each([
        ["ON_TRACK", "On track"],
        ["WARNING", "Warning"],
        ["EXCEEDED", "Exceeded"],
    ] as const)("%s conveys status with text and not color alone", async (status, text) => {
        await renderUi(<ProgressBar percent={50} status={status} />);
        expect(screen.getByText(text)).toBeTruthy();
        expect(screen.getByRole("progressbar", { name: `${text}, 50 %` })).toBeTruthy();
    });

    test("clamps the fill but reports the real percentage", async () => {
        await renderUi(<ProgressBar percent={130} status="EXCEEDED" />);
        const fill = StyleSheet.flatten(screen.getByTestId("progress-fill").props.style);
        expect(fill.width).toBe("100%");
        expect(screen.getByText("130 %")).toBeTruthy();
    });
});

test("ListRow is pressable with a combined label", async () => {
    const onPress = jest.fn();
    await renderUi(<ListRow title="Groceries" subtitle="Today" onPress={onPress} />);
    await fireEvent.press(screen.getByRole("button", { name: "Groceries, Today" }));
    expect(onPress).toHaveBeenCalled();
});

describe("ConfirmSheet", () => {
    test("requires the explicit second action and is dismissible", async () => {
        const onConfirm = jest.fn();
        const onCancel = jest.fn();
        await renderUi(
            <ConfirmSheet
                visible
                title="Delete?"
                message="Cannot be undone"
                confirmLabel="Delete"
                onConfirm={onConfirm}
                onCancel={onCancel}
            />,
        );
        expect(screen.getByRole("header", { name: "Delete?" })).toBeTruthy();
        expect(onConfirm).not.toHaveBeenCalled();
        await fireEvent.press(screen.getByRole("button", { name: "Cancel" }));
        expect(onCancel).toHaveBeenCalledTimes(1);
        await fireEvent.press(screen.getAllByRole("button", { name: "Close", hidden: true })[0]!);
        expect(onCancel).toHaveBeenCalledTimes(2);
        await fireEvent.press(screen.getByRole("button", { name: "Delete" }));
        expect(onConfirm).toHaveBeenCalledTimes(1);
    });

    test("renders nothing when hidden", async () => {
        await renderUi(
            <ConfirmSheet
                visible={false}
                title="T"
                message="M"
                confirmLabel="Go"
                onConfirm={jest.fn()}
                onCancel={jest.fn()}
            />,
        );
        expect(screen.queryByText("M")).toBeNull();
    });
});

describe("states", () => {
    test("Skeleton announces loading", async () => {
        await renderUi(<Skeleton variant="row" />);
        expect(screen.getByRole("progressbar", { name: "Loading" })).toBeBusy();
    });

    test("EmptyState shows message and optional action", async () => {
        const onAction = jest.fn();
        await renderUi(
            <EmptyState
                title="None"
                message="Nothing here"
                actionLabel="Add"
                onAction={onAction}
            />,
        );
        expect(screen.getByText("Nothing here")).toBeTruthy();
        await fireEvent.press(screen.getByRole("button", { name: "Add" }));
        expect(onAction).toHaveBeenCalled();
    });

    test("EmptyState without action has no button", async () => {
        await renderUi(<EmptyState title="None" message="Nothing" />);
        expect(screen.queryByRole("button")).toBeNull();
    });

    test("ErrorState shows a friendly message and retries", async () => {
        const onRetry = jest.fn();
        await renderUi(<ErrorState onRetry={onRetry} />);
        expect(screen.getByText("Something went wrong. Please try again.")).toBeTruthy();
        await fireEvent.press(screen.getByRole("button", { name: "Retry" }));
        expect(onRetry).toHaveBeenCalled();
    });
});

describe("MoneyText", () => {
    test("BR_SCP_03_labels_scope_and_formats_with_explicit_currency", async () => {
        await renderUi(
            <MoneyText
                money={{ amount: "1234.50", currency: "EUR" }}
                scope="PERSONAL"
                locale="en-US"
            />,
        );
        expect(screen.getByText("€1,234.50")).toBeTruthy();
        expect(screen.getByText("Personal")).toBeTruthy();
        expect(screen.getByLabelText("€1,234.50, Personal")).toBeTruthy();
    });

    test("uses tabular figures", async () => {
        await renderUi(<MoneyText money={{ amount: "1.00", currency: "USD" }} locale="en-US" />);
        const style = StyleSheet.flatten(screen.getByText("$1.00").props.style);
        expect(style.fontVariant).toContain("tabular-nums");
    });
});

describe("Toast", () => {
    function Trigger() {
        const toast = useToast();
        return <Button label="go" onPress={() => toast("Saved!")} />;
    }

    test("shows an alert and auto-dismisses", async () => {
        jest.useFakeTimers();
        await renderUi(<Trigger />);
        await fireEvent.press(screen.getByRole("button", { name: "go" }));
        expect(screen.getByRole("alert")).toBeTruthy();
        expect(screen.getByText("Saved!")).toBeTruthy();
        await act(async () => {
            jest.advanceTimersByTime(5000);
        });
        expect(screen.queryByText("Saved!")).toBeNull();
        jest.useRealTimers();
    });
});

describe("Screen and Sheet accessibility", () => {
    test("Screen shows a close control only when onClose is given and it works", async () => {
        const onClose = jest.fn();
        await renderUi(
            <Screen onClose={onClose}>
                <Text>Body</Text>
            </Screen>,
        );
        await fireEvent.press(screen.getByRole("button", { name: "Close" }));
        expect(onClose).toHaveBeenCalledTimes(1);
    });

    test("Screen without onClose has no close control and scroll=false skips the ScrollView", async () => {
        await renderUi(
            <Screen scroll={false}>
                <Text>Body</Text>
            </Screen>,
        );
        expect(screen.queryByRole("button", { name: "Close" })).toBeNull();
        expect(screen.getByText("Body")).toBeTruthy();
    });

    test("Sheet exposes a Close button inside the modal view", async () => {
        const onClose = jest.fn();
        await renderUi(
            <Sheet visible title="Title" onClose={onClose}>
                <Text>Content</Text>
            </Sheet>,
        );
        const buttons = screen.getAllByRole("button", { name: "Close" });
        await fireEvent.press(buttons[buttons.length - 1]!);
        expect(onClose).toHaveBeenCalled();
    });
});
