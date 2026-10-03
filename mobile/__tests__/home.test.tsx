import { render, screen } from "@testing-library/react-native";
import Home from "../app/index";

test("placeholder home exposes an accessible header", async () => {
    await render(<Home />);
    expect(screen.getByRole("header", { name: "CoupleFinance" })).toBeTruthy();
});
