import { type ComponentProps } from "react";
import { type Control, Controller, type FieldPath, type FieldValues } from "react-hook-form";
import { TextInput } from "@/shared/ui";

type InputProps = Omit<
    ComponentProps<typeof TextInput>,
    "value" | "onChangeText" | "onBlur" | "error"
>;

interface Props<T extends FieldValues> extends InputProps {
    control: Control<T>;
    name: FieldPath<T>;
    /** Server-side message for this field (FieldViolation), shown when there is no local error. */
    serverError?: string | undefined;
}

/** react-hook-form binding for the design-system TextInput; errors are announced by the primitive. */
export function FormTextField<T extends FieldValues>({
    control,
    name,
    serverError,
    ...rest
}: Props<T>) {
    return (
        <Controller
            control={control}
            name={name}
            render={({ field, fieldState }) => (
                <TextInput
                    {...rest}
                    value={String(field.value ?? "")}
                    onChangeText={field.onChange}
                    onBlur={field.onBlur}
                    error={fieldState.error?.message ?? serverError}
                />
            )}
        />
    );
}
