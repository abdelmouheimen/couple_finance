package com.couplefinance.platform.authz;

import static com.couplefinance.platform.authz.OperationFixture.authenticated;
import static com.couplefinance.platform.authz.OperationFixture.household;
import static com.couplefinance.platform.authz.OperationFixture.householdWithoutPersonalData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpMethod;

/**
 * Classification of every operation of the API (security.md section 4.1 point 6, section 4.2). Adding an endpoint
 * without registering it here fails {@code AuthorizationMatrixIntegrationTest}: that is the Definition of Done hook
 * that makes later Issues extend the matrix.
 *
 * <p>Registration rules: an operation taking a resource id MUST be registered {@code addressesById = true} (it then
 * gets the 404 cross-household check); {@code createHousehold} is AUTHENTICATED_ONLY and therefore excluded from the
 * dissolved-household write check (it creates a new household, it does not write to an existing one).
 */
public final class OperationRegistry {

    private static final String EXEMPT_CATEGORIES =
            "categories are household-wide data; no PERSONAL attribute exists (BR-EXP-07 does not apply)";

    private static final String EXEMPT_INVITATIONS =
            "invitations hold no PERSONAL financial data (BR-EXP-07 does not apply); only the caller's own"
                    + " invitations are listed or revoked and the code is returned only at creation (BR-HH-04)";

    private static final String EXEMPT_BUDGETS =
            "budgets are household-level limits; personal budgets are out of scope (BR-BUD-02) and no PERSONAL"
                    + " attribute exists";

    private OperationRegistry() {
    }

    /** Problems of a registry against the contract; empty when every operation is classified and fixtured. */
    public static List<String> problems(Collection<ApiOperation> operations, Map<String, OperationFixture> registry) {
        List<String> problems = new ArrayList<>();
        List<String> known = new ArrayList<>();
        for (ApiOperation operation : operations) {
            known.add(operation.operationId());
            OperationFixture fixture = registry.get(operation.operationId());
            if (fixture == null) {
                problems.add(operation + " is not classified: register it in OperationRegistry");
                continue;
            }
            if (fixture.kind() == OperationKind.PUBLIC) {
                if (operation.secured()) {
                    problems.add(operation + " is registered PUBLIC but the contract requires authentication");
                }
                continue;
            }
            if (!operation.secured()) {
                problems.add(operation + " is not secured in the contract but registered " + fixture.kind());
            }
            if (fixture.call() == null) {
                problems.add(operation + " has no fixture call: it cannot be silently skipped");
            }
            if (fixture.kind() == OperationKind.HOUSEHOLD_SCOPED) {
                boolean probe = fixture.privacyProbe() != null;
                boolean exemption = fixture.privacyExemption() != null && !fixture.privacyExemption().isBlank();
                if (probe == exemption) {
                    problems.add(operation + " needs exactly one of a privacy probe or a justified exemption");
                }
            }
        }
        registry.keySet().stream().filter(id -> !known.contains(id))
                .forEach(id -> problems.add("registry entry " + id + " matches no operation of the contract"));
        return problems;
    }

    public static Map<String, OperationFixture> defaults() {
        Map<String, OperationFixture> registry = new LinkedHashMap<>();

        registry.put("createHousehold", authenticated(s -> Call.with(HttpMethod.POST, "/api/v1/households",
                "{\"name\":\"Another foyer\"}")));
        registry.put("getCurrentHousehold", authenticated(s -> Call.get("/api/v1/households/me")));

        registry.put("createInvitation", householdWithoutPersonalData(s -> Call.without(HttpMethod.POST,
                "/api/v1/households/me/invitations"), false, EXEMPT_INVITATIONS));
        registry.put("listInvitations", householdWithoutPersonalData(s -> Call.get(
                "/api/v1/households/me/invitations"), false, EXEMPT_INVITATIONS));
        registry.put("revokeInvitation", householdWithoutPersonalData(s -> Call.without(HttpMethod.DELETE,
                "/api/v1/households/me/invitations/" + s.target().invitation()), true, EXEMPT_INVITATIONS));

        registry.put("listCategories", householdWithoutPersonalData(s -> Call.get("/api/v1/categories"), false,
                EXEMPT_CATEGORIES));
        registry.put("createCategory", householdWithoutPersonalData(s -> Call.with(HttpMethod.POST,
                "/api/v1/categories", "{\"name\":\"Cat " + shortId() + "\"}"), false, EXEMPT_CATEGORIES));
        registry.put("updateCategory", householdWithoutPersonalData(s -> Call.withIfMatch(HttpMethod.PATCH,
                "/api/v1/categories/" + s.target().customCategory(), "{\"name\":\"Renamed " + shortId() + "\"}",
                "\"0\""), true, EXEMPT_CATEGORIES));

        registry.put("suggestCategory", household(s -> Call.get("/api/v1/category-suggestions?merchant="
                + s.target().ruleMerchant() + "&sharingType=PERSONAL"), false, PrivacyProbes::suggestions));
        registry.put("setMerchantRule", householdWithoutPersonalData(s -> Call.with(HttpMethod.PUT,
                "/api/v1/merchant-rules", "{\"merchant\":\"" + s.target().ruleMerchant() + "\",\"categoryId\":\""
                        + SeededHousehold.SYSTEM_GROCERIES + "\",\"sharingType\":\"SHARED\"}"), false,
                "writes only the caller's own scope and echoes the caller's input; reads of the owner's private"
                        + " rule are covered by the suggestCategory probe"));

        registry.put("listExpenses", household(s -> Call.get("/api/v1/expenses?scope=HOUSEHOLD&limit=100"), false,
                PrivacyProbes::expenseList));
        registry.put("createExpense", householdWithoutPersonalData(s -> Call.with(HttpMethod.POST,
                "/api/v1/expenses", AuthzWorld.expenseBody(s.caller(), "SHARED", "Created " + shortId(), null)),
                false, "returns only the expense it just created for the caller"));
        registry.put("getExpense", household(s -> Call.get("/api/v1/expenses/" + s.target().personalExpense()),
                true, PrivacyProbes::expenseDetail));
        registry.put("getExpenseAudit", household(s -> Call.get("/api/v1/expenses/" + s.target().personalExpense()
                + "/audit"), true, PrivacyProbes::expenseAudit));
        registry.put("updateExpense", household(s -> Call.withIfMatch(HttpMethod.PUT,
                "/api/v1/expenses/" + s.target().personalExpense(),
                AuthzWorld.expenseBody(s.caller(), "PERSONAL", "Edited " + shortId(), null), "\"0\""), true,
                PrivacyProbes::expenseUpdate));
        registry.put("deleteExpense", household(s -> Call.without(HttpMethod.DELETE,
                "/api/v1/expenses/" + s.target().personalExpense()), true, PrivacyProbes::expenseDelete));
        registry.put("restoreExpense", household(s -> Call.without(HttpMethod.POST,
                "/api/v1/expenses/" + s.target().personalExpense() + "/restore"), true,
                PrivacyProbes::expenseRestore));

        registry.put("getBudget", householdWithoutPersonalData(s -> Call.get("/api/v1/budgets/"
                + s.target().periodStart()), false, EXEMPT_BUDGETS + "; keyed by a period of the caller's own "
                + "household calendar, not by a foreign id (a foreign caller reads its own household's budget)"));
        registry.put("setBudget", householdWithoutPersonalData(s -> Call.withIfMatch(HttpMethod.PUT,
                "/api/v1/budgets/" + s.target().periodStart(),
                "{\"overallLimit\":{\"amount\":\"2000.00\",\"currency\":\"EUR\"}}", "\"0\""), false,
                EXEMPT_BUDGETS + "; the budget is keyed by a period of the caller's own household calendar, not by "
                        + "a foreign id: a foreign caller only ever reads or writes its own household's budget"));
        registry.put("getPeriodAnalytics", household(s -> Call.get("/api/v1/analytics/periods/"
                + s.target().periodStart() + "?scope=HOUSEHOLD"), false, PrivacyProbes::periodAnalytics));
        return registry;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
