import { z } from "zod";
import { strings } from "@/shared/i18n/strings";

const email = z
    .string()
    .trim()
    .max(254, strings.auth.emailRequired)
    .pipe(z.email(strings.auth.emailRequired));

export const signInSchema = z.object({
    email,
    password: z
        .string()
        .min(1, strings.auth.passwordRequired)
        .max(128, strings.auth.passwordTooLong),
});
export type SignInValues = z.infer<typeof signInSchema>;

export const signUpSchema = z.object({
    displayName: z
        .string()
        .trim()
        .min(1, strings.auth.displayNameRequired)
        .max(60, strings.auth.displayNameTooLong),
    email,
    password: z
        .string()
        .min(10, strings.auth.passwordTooShort)
        .max(128, strings.auth.passwordTooLong),
});
export type SignUpValues = z.infer<typeof signUpSchema>;

export const verifyCodeSchema = z.object({
    token: z.string().trim().min(1, strings.auth.verifyCodeRequired).max(128),
});
export type VerifyCodeValues = z.infer<typeof verifyCodeSchema>;
