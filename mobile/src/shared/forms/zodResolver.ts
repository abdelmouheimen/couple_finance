import { type FieldErrors, type FieldValues, type Resolver } from "react-hook-form";
import { type ZodType } from "zod";

/** Minimal react-hook-form resolver for a zod schema (messages are already human-readable). */
export function zodResolver<T extends FieldValues>(schema: ZodType<unknown, T>): Resolver<T> {
    return (values) => {
        const result = schema.safeParse(values);
        if (result.success) return { values: result.data as T, errors: {} };
        const errors: Record<string, { type: string; message: string }> = {};
        for (const issue of result.error.issues) {
            const key = String(issue.path[0] ?? "root");
            errors[key] ??= { type: issue.code, message: issue.message };
        }
        return { values: {}, errors: errors as FieldErrors<T> };
    };
}
