import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { FormTextField } from "@/shared/forms/FormTextField";
import { zodResolver } from "@/shared/forms/zodResolver";
import { errorMessage, fieldErrors } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { Button, Text } from "@/shared/ui";
import { type CreateHouseholdInput } from "./householdApi";

const schema = z.object({
    name: z
        .string()
        .trim()
        .min(1, strings.household.nameRequired)
        .max(100, strings.household.nameTooLong),
    currency: z
        .string()
        .trim()
        .transform((v) => v.toUpperCase())
        .pipe(z.string().regex(/^[A-Z]{3}$/, strings.household.currencyInvalid)),
    timezone: z.string().trim().min(1, strings.household.timezoneRequired).max(64),
    periodStartDay: z
        .string()
        .trim()
        .regex(/^\d{1,2}$/, strings.household.periodStartDayInvalid)
        .refine((v) => Number(v) >= 1 && Number(v) <= 28, strings.household.periodStartDayInvalid),
});
type Values = z.input<typeof schema>;

/** BR-HH-16 defaults, mirrored for display only; when options stay collapsed the server applies them. */
const DEFAULTS: Values = {
    name: "",
    currency: "EUR",
    timezone: "Europe/Paris",
    periodStartDay: "1",
};

interface Props {
    onSubmit: (input: CreateHouseholdInput) => Promise<void>;
}

export function CreateHouseholdForm({ onSubmit }: Props) {
    const [expanded, setExpanded] = useState(false);
    const [error, setError] = useState<unknown>(null);
    const { control, handleSubmit, formState } = useForm<Values>({
        resolver: zodResolver(schema),
        defaultValues: DEFAULTS,
    });
    const submit = handleSubmit(async (raw) => {
        setError(null);
        const values = schema.parse(raw);
        const input: CreateHouseholdInput = expanded
            ? {
                  name: values.name,
                  currency: values.currency,
                  timezone: values.timezone,
                  periodStartDay: Number(values.periodStartDay),
              }
            : { name: values.name };
        try {
            await onSubmit(input);
        } catch (e) {
            setError(e);
        }
    });
    const server = fieldErrors(error);
    return (
        <>
            <FormTextField
                control={control}
                name="name"
                label={strings.household.name}
                serverError={server.name}
                returnKeyType="done"
                onSubmitEditing={() => void submit()}
            />
            {expanded ? (
                <>
                    <FormTextField
                        control={control}
                        name="currency"
                        label={strings.household.currency}
                        serverError={server.currency}
                        autoCapitalize="characters"
                        autoCorrect={false}
                        maxLength={3}
                        returnKeyType="next"
                    />
                    <FormTextField
                        control={control}
                        name="timezone"
                        label={strings.household.timezone}
                        serverError={server.timezone}
                        autoCapitalize="none"
                        autoCorrect={false}
                        returnKeyType="next"
                    />
                    <FormTextField
                        control={control}
                        name="periodStartDay"
                        label={strings.household.periodStartDay}
                        serverError={server.periodStartDay}
                        keyboardType="number-pad"
                        maxLength={2}
                        returnKeyType="done"
                    />
                </>
            ) : null}
            <Button
                variant="secondary"
                label={expanded ? strings.household.lessOptions : strings.household.moreOptions}
                onPress={() => setExpanded((v) => !v)}
            />
            {error ? (
                <Text tone="danger" accessibilityLiveRegion="polite">
                    {errorMessage(error)}
                </Text>
            ) : null}
            <Button
                label={strings.household.createAction}
                loading={formState.isSubmitting}
                onPress={() => void submit()}
            />
        </>
    );
}
