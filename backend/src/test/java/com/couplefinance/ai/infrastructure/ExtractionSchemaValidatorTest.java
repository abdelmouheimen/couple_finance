package com.couplefinance.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** BR-RCP-03: model output either matches the ai.md §4.2 schema exactly or is a failure (never best-effort). */
class ExtractionSchemaValidatorTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final ExtractionSchemaValidator validator = new ExtractionSchemaValidator(json);

    private ExtractionSchemaValidator.Outcome validate(Consumer<ObjectNode> mutation) {
        ObjectNode root = (ObjectNode) json.readTree(FakeDocumentIntelligenceAdapter.FIXTURE);
        mutation.accept(root);
        return validator.validate(json.writeValueAsString(root));
    }

    private void assertRejected(Consumer<ObjectNode> mutation, String violation) {
        ExtractionSchemaValidator.Outcome outcome = validate(mutation);
        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.extraction()).isEmpty();
        assertThat(outcome.violations()).anySatisfy(v -> assertThat(v).contains(violation));
    }

    @Test
    void the_reference_transcription_is_valid_and_kept_as_printed() {
        ExtractionSchemaValidator.Outcome outcome = validate(root -> { });

        assertThat(outcome.valid()).isTrue();
        assertThat(outcome.violations()).isEmpty();
        var extraction = outcome.extraction().orElseThrow();
        assertThat(extraction.total()).contains("13,78");
        assertThat(extraction.handwrittenTotal()).isEmpty();
        assertThat(extraction.observedFormats().decimalSeparator()).isEqualTo(",");
        assertThat(extraction.lineItems()).hasSize(4);
        assertThat(extraction.confidence().total()).hasValueSatisfying(c -> assertThat(c).isEqualByComparingTo("0.99"));
    }

    @Test
    void optional_parts_may_be_absent() {
        ExtractionSchemaValidator.Outcome outcome = validate(root -> root.remove(java.util.List.of("confidence",
                "lineItems", "payments", "taxLines", "merchantName", "total")));

        assertThat(outcome.valid()).isTrue();
        assertThat(outcome.extraction().orElseThrow().total()).isEmpty();
        assertThat(outcome.extraction().orElseThrow().confidence().total()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "[]", "\"13,78\"", "null", "{\"documentType\":"})
    void output_that_is_not_a_json_object_is_rejected(String raw) {
        assertThat(validator.validate(raw).valid()).isFalse();
    }

    @Test
    void BR_MON_08_a_numeric_amount_is_rejected_amounts_are_strings_as_printed() {
        assertRejected(root -> root.put("total", 13.78), "total must be a string");
        assertRejected(root -> ((ObjectNode) root.get("lineItems").get(0)).put("amount", 1.29), "amount");
        assertRejected(root -> ((ObjectNode) root.get("payments").get(0)).remove("amount"), "amount is required");
    }

    @Test
    void unknown_properties_and_enumeration_values_are_rejected() {
        assertRejected(root -> root.put("categoryId", "groceries"), "unknown property categoryId");
        assertRejected(root -> root.put("documentType", "TICKET"), "documentType must be one of");
        assertRejected(root -> root.remove("taxMode"), "taxMode must be one of");
        assertRejected(root -> root.remove("observedFormats"), "observedFormats is required");
        assertRejected(root -> ((ObjectNode) root.get("observedFormats")).put("dateOrder", "DYM"), "dateOrder");
        assertRejected(root -> ((ObjectNode) root.get("observedFormats")).put("locale", "fr"), "unknown property");
        assertRejected(root -> ((ObjectNode) root.get("lineItems").get(0)).put("type", "PRODUCT"), "type must be");
        assertRejected(root -> ((ObjectNode) root.get("payments").get(0)).put("method", "BITCOIN"), "method");
        assertRejected(root -> root.putArray("taxLines").add("20%"), "taxLines[] must be an object");
        assertRejected(root -> root.put("lineItems", "none"), "lineItems must be an array");
    }

    @Test
    void oversized_output_is_rejected() {
        assertRejected(root -> root.put("merchantName", "x".repeat(201)), "merchantName is longer");
        assertRejected(root -> root.put("total", "1".repeat(41)), "total is longer");
        assertRejected(root -> {
            var items = root.putArray("lineItems");
            for (int i = 0; i <= ExtractionSchemaValidator.MAX_LINE_ITEMS; i++) {
                items.addObject().put("type", "ITEM").put("description", "x").put("amount", "1,00");
            }
        }, "lineItems has more than 300");
    }

    @Test
    void BR_RCP_11_a_confidence_must_be_a_number_between_0_and_1() {
        assertRejected(root -> ((ObjectNode) root.get("confidence")).put("total", "high"), "must be a number");
        assertRejected(root -> ((ObjectNode) root.get("confidence")).put("total", 1.5), "between 0 and 1");
        assertRejected(root -> ((ObjectNode) root.get("confidence")).put("currency", -0.1), "between 0 and 1");
        assertRejected(root -> root.put("confidence", 0.9), "confidence must be an object");
    }
}
