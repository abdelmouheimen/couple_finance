package com.couplefinance.expense.application;

import java.util.Optional;
import java.util.UUID;

import com.couplefinance.identity.api.CurrentUser;
import com.couplefinance.shared.idempotency.IdempotencyKey;
import com.couplefinance.shared.idempotency.IdempotencyService;
import com.couplefinance.shared.idempotency.IdempotentResponse;
import com.couplefinance.shared.idempotency.IdempotentResult;
import com.couplefinance.shared.idempotency.RequestHash;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Create-expense use case with {@code Idempotency-Key} handling (BR-EXP-13): the key is claimed before any
 * processing, in the scope of the authenticated user; the creation itself is the single transaction of
 * {@link ExpenseCreator}. This class is deliberately NOT transactional: the idempotency claim and response commit
 * independently of the creation.
 */
@Service
public class CreateExpenseService {

    static final String OPERATION = "expense.create";

    private final CurrentUser currentUser;
    private final ExpenseCreator creator;
    private final IdempotencyService idempotency;
    private final JsonMapper jsonMapper;

    CreateExpenseService(CurrentUser currentUser, ExpenseCreator creator, IdempotencyService idempotency,
            JsonMapper jsonMapper) {
        this.currentUser = currentUser;
        this.creator = creator;
        this.idempotency = idempotency;
        this.jsonMapper = jsonMapper;
    }

    /**
     * @param key present when the client sent an {@code Idempotency-Key}: the same key and request return the
     *            original result without creating a second expense
     */
    public Created create(CreateExpenseCommand command, Optional<IdempotencyKey> key) {
        UUID user = currentUser.requireActiveUser().value();
        if (key.isEmpty()) {
            return new Created(creator.create(command), false);
        }
        IdempotentResult result = idempotency.execute(user, key.get(), OPERATION, hash(command), () -> {
            ExpenseView view = creator.create(command);
            return new IdempotentResponse(HttpStatus.CREATED.value(), jsonMapper.writeValueAsString(view));
        });
        String body = result.response().jsonBody();
        return new Created(jsonMapper.readValue(body, ExpenseView.class), result.replayed());
    }

    /** The created expense and whether it is a replay of an earlier identical request. */
    public record Created(ExpenseView expense, boolean replayed) {}

    /** Every field that changes the outcome, in canonical text form (the user is the key scope). */
    private static byte[] hash(CreateExpenseCommand command) {
        RequestHash.Builder hash = RequestHash.forOperation(OPERATION)
                .field("amount", command.amount().toDecimalString())
                .field("currency", command.amount().currency().value())
                .field("date", command.date().toString())
                .field("paidBy", command.paidByUserId().toString())
                .field("sharing", command.sharingType().name())
                .field("merchant", command.merchant())
                .field("note", command.note())
                .field("itemCount", Integer.toString(command.items().size()));
        for (int i = 0; i < command.items().size(); i++) {
            CreateExpenseCommand.Item item = command.items().get(i);
            hash.field("item." + i + ".category", item.categoryId().toString())
                    .field("item." + i + ".amount", item.amount().toDecimalString())
                    .field("item." + i + ".currency", item.amount().currency().value())
                    .field("item." + i + ".label", item.label());
        }
        return hash.build();
    }
}
