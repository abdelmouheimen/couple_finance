import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import * as Clipboard from "expo-clipboard";
import { useState } from "react";
import { AccessibilityInfo, Share, View } from "react-native";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { spacing } from "@/shared/ui/theme/tokens";
import { strings } from "@/shared/i18n/strings";
import {
    Button,
    Card,
    ConfirmSheet,
    EmptyState,
    ErrorState,
    ListRow,
    Sheet,
    Skeleton,
    Text,
    useToast,
} from "@/shared/ui";
import {
    type CreatedInvitation,
    createInvitation,
    type Invitation,
    listInvitations,
    revokeInvitation,
} from "./householdApi";

const KEY = ["household", "invitations"] as const;

function formatExpiry(iso: string): string {
    return new Date(iso).toLocaleDateString(undefined, { dateStyle: "medium" });
}

/** Invite the partner: create (code shown once), list open invitations, revoke after confirmation. */
export function InvitationsSection({ readOnly }: { readOnly: boolean }) {
    const queryClient = useQueryClient();
    const toast = useToast();
    // The code lives in component state only: never cached, persisted or logged.
    const [created, setCreated] = useState<CreatedInvitation | null>(null);
    const [toRevoke, setToRevoke] = useState<Invitation | null>(null);
    const [actionError, setActionError] = useState<string | null>(null);

    const list = useQuery({ queryKey: KEY, queryFn: listInvitations, retry: false });
    const create = useMutation({
        mutationFn: createInvitation,
        onSuccess: (invitation) => {
            setActionError(null);
            setCreated(invitation);
            void queryClient.invalidateQueries({ queryKey: KEY });
        },
        onError: (e) => setActionError(errorMessage(e)),
    });
    const revoke = useMutation({
        mutationFn: revokeInvitation,
        onSuccess: () => {
            setToRevoke(null);
            void queryClient.invalidateQueries({ queryKey: KEY });
        },
        onError: (e) => {
            setToRevoke(null);
            setActionError(errorMessage(e));
        },
    });

    const copy = async () => {
        if (!created) return;
        await Clipboard.setStringAsync(created.code);
        toast(strings.household.copied, "success");
        AccessibilityInfo.announceForAccessibility(strings.household.copied);
    };

    return (
        <View style={{ gap: spacing.md }}>
            <Text variant="title" accessibilityRole="header">
                {strings.household.invitationsTitle}
            </Text>
            {list.isPending ? <Skeleton variant="row" /> : null}
            {list.isError ? (
                <ErrorState
                    message={errorMessage(list.error)}
                    onRetry={() => void list.refetch()}
                />
            ) : null}
            {list.data && list.data.length === 0 ? (
                <EmptyState
                    title={strings.household.inviteEmptyTitle}
                    message={strings.household.inviteEmptyMessage}
                    {...(readOnly
                        ? {}
                        : {
                              actionLabel: strings.household.invitePartner,
                              onAction: () => create.mutate(),
                          })}
                />
            ) : null}
            {list.data && list.data.length > 0 ? (
                <Card>
                    {list.data.map((invitation) => (
                        <ListRow
                            key={invitation.id}
                            title={strings.household.codeTitle}
                            subtitle={strings.household.invitationExpires(
                                formatExpiry(invitation.expiresAt),
                            )}
                            trailing={
                                readOnly ? null : (
                                    <Button
                                        variant="secondary"
                                        label={strings.household.revoke}
                                        onPress={() => setToRevoke(invitation)}
                                    />
                                )
                            }
                        />
                    ))}
                </Card>
            ) : null}
            {actionError ? (
                <Text tone="danger" accessibilityLiveRegion="polite">
                    {actionError}
                </Text>
            ) : null}
            {list.data && list.data.length > 0 && !readOnly ? (
                <Button
                    label={strings.household.invitePartner}
                    loading={create.isPending}
                    onPress={() => create.mutate()}
                />
            ) : null}

            <Sheet
                visible={created !== null}
                title={strings.household.codeTitle}
                onClose={() => setCreated(null)}
            >
                {created ? (
                    <>
                        <Text
                            variant="headline"
                            selectable
                            accessibilityLabel={`${strings.household.codeTitle}: ${created.code
                                .split("")
                                .join(" ")}`}
                        >
                            {created.code}
                        </Text>
                        <Text tone="secondary">
                            {strings.household.codeExpires(formatExpiry(created.expiresAt))}
                        </Text>
                        <Text tone="secondary">{strings.household.codeWarning}</Text>
                        <Button label={strings.household.copy} onPress={() => void copy()} />
                        <Button
                            variant="secondary"
                            label={strings.household.share}
                            onPress={() => void Share.share({ message: created.code })}
                        />
                        <Button
                            variant="secondary"
                            label={strings.household.done}
                            onPress={() => setCreated(null)}
                        />
                    </>
                ) : null}
            </Sheet>
            <ConfirmSheet
                visible={toRevoke !== null}
                title={strings.household.revokeTitle}
                message={strings.household.revokeMessage}
                confirmLabel={strings.household.revoke}
                onConfirm={() => toRevoke && revoke.mutate(toRevoke.id)}
                onCancel={() => setToRevoke(null)}
            />
        </View>
    );
}
