import { fireEvent, screen, waitFor } from "@testing-library/react-native";
import { CreateHouseholdForm } from "@/features/household/CreateHouseholdForm";
import { ApiError } from "@/shared/api/problem";
import { strings } from "@/shared/i18n/strings";
import { renderUi } from "@/shared/ui/testing";
import { SignInForm } from "./SignInForm";
import { SignUpForm } from "./SignUpForm";

describe("SignInForm", () => {
    test("validates before submitting", async () => {
        const onSubmit = jest.fn();
        await renderUi(<SignInForm onSubmit={onSubmit} />);
        await fireEvent.press(screen.getByRole("button", { name: strings.auth.signInAction }));
        expect(await screen.findByText(strings.auth.emailRequired)).toBeTruthy();
        expect(onSubmit).not.toHaveBeenCalled();
    });

    test("submits once and shows the mapped error without raw codes", async () => {
        const onSubmit = jest.fn(async () => {
            throw new ApiError({ code: "INVALID_CREDENTIALS", status: 401, title: "x" });
        });
        await renderUi(<SignInForm onSubmit={onSubmit} />);
        await fireEvent.changeText(screen.getByLabelText(strings.auth.email), "a@b.co");
        await fireEvent.changeText(screen.getByLabelText(strings.auth.password), "pw");
        await fireEvent.press(screen.getByRole("button", { name: strings.auth.signInAction }));
        expect(await screen.findByText(strings.errors.invalidCredentials)).toBeTruthy();
        expect(screen.queryByText("INVALID_CREDENTIALS")).toBeNull();
        expect(onSubmit).toHaveBeenCalledTimes(1);
    });

    test("password visibility toggle is labelled", async () => {
        await renderUi(<SignInForm onSubmit={jest.fn()} />);
        await fireEvent.press(screen.getByRole("button", { name: strings.auth.showPassword }));
        expect(screen.getByRole("button", { name: strings.auth.hidePassword })).toBeTruthy();
    });
});

describe("SignUpForm", () => {
    test("enforces the 10-character password minimum", async () => {
        const onSubmit = jest.fn();
        await renderUi(<SignUpForm onSubmit={onSubmit} />);
        await fireEvent.changeText(screen.getByLabelText(strings.auth.displayName), "Ada");
        await fireEvent.changeText(screen.getByLabelText(strings.auth.email), "a@b.co");
        await fireEvent.changeText(screen.getByLabelText(strings.auth.passwordNew), "short");
        await fireEvent.press(screen.getByRole("button", { name: strings.auth.signUpAction }));
        expect(await screen.findByText(strings.auth.passwordTooShort)).toBeTruthy();
        expect(onSubmit).not.toHaveBeenCalled();
    });

    test("maps server field violations to the field", async () => {
        const onSubmit = jest.fn(async () => {
            throw new ApiError({
                code: "VALIDATION_FAILED",
                status: 400,
                title: "x",
                errors: [{ field: "email", message: "raw server text" }],
            });
        });
        await renderUi(<SignUpForm onSubmit={onSubmit} />);
        await fireEvent.changeText(screen.getByLabelText(strings.auth.displayName), "Ada");
        await fireEvent.changeText(screen.getByLabelText(strings.auth.email), "a@b.co");
        await fireEvent.changeText(
            screen.getByLabelText(strings.auth.passwordNew),
            "long-enough-pw",
        );
        await fireEvent.press(screen.getByRole("button", { name: strings.auth.signUpAction }));
        expect(await screen.findByText(strings.errors.invalidField)).toBeTruthy();
        expect(screen.queryByText("raw server text")).toBeNull();
    });
});

describe("CreateHouseholdForm (BR-HH-16)", () => {
    test("a name alone is enough: only the name is sent, the server applies defaults", async () => {
        const onSubmit = jest.fn(async () => undefined);
        await renderUi(<CreateHouseholdForm onSubmit={onSubmit} />);
        await fireEvent.changeText(screen.getByLabelText(strings.household.name), "Home");
        await fireEvent.press(screen.getByRole("button", { name: strings.household.createAction }));
        await waitFor(() => expect(onSubmit).toHaveBeenCalledWith({ name: "Home" }));
    });

    test("requires a name", async () => {
        const onSubmit = jest.fn();
        await renderUi(<CreateHouseholdForm onSubmit={onSubmit} />);
        await fireEvent.press(screen.getByRole("button", { name: strings.household.createAction }));
        expect(await screen.findByText(strings.household.nameRequired)).toBeTruthy();
        expect(onSubmit).not.toHaveBeenCalled();
    });

    test("more options are editable and sent explicitly", async () => {
        const onSubmit = jest.fn(async () => undefined);
        await renderUi(<CreateHouseholdForm onSubmit={onSubmit} />);
        await fireEvent.changeText(screen.getByLabelText(strings.household.name), "Home");
        await fireEvent.press(screen.getByRole("button", { name: strings.household.moreOptions }));
        await fireEvent.changeText(screen.getByLabelText(strings.household.periodStartDay), "15");
        await fireEvent.press(screen.getByRole("button", { name: strings.household.createAction }));
        await waitFor(() =>
            expect(onSubmit).toHaveBeenCalledWith({
                name: "Home",
                currency: "EUR",
                timezone: "Europe/Paris",
                periodStartDay: 15,
            }),
        );
    });
});
