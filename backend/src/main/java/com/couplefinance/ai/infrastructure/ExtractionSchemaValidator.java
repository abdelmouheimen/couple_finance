package com.couplefinance.ai.infrastructure;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.couplefinance.ai.api.DocumentExtraction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict validation of raw model output against the ai.md §4.2 extraction schema (BR-RCP-03): JSON object, no
 * additional properties, known enumerations, string types and bounded lengths, at most 300 line items. Output that
 * does not match is a failure: it is never "best effort" parsed (CLAUDE.md §12.2).
 */
final class ExtractionSchemaValidator {

    static final int MAX_LINE_ITEMS = 300;
    static final int MAX_LIST = 50;
    static final int MAX_TEXT = 200;
    static final int MAX_VALUE = 40;

    static final Set<String> DOCUMENT_TYPES =
            Set.of("RECEIPT", "CARD_SLIP", "INVOICE", "QUOTE", "REFUND_RECEIPT", "OTHER");
    static final Set<String> TAX_MODES = Set.of("INCLUSIVE", "EXCLUSIVE", "UNKNOWN");
    static final Set<String> LINE_TYPES = Set.of("ITEM", "DISCOUNT", "DEPOSIT", "DEPOSIT_RETURN", "TIP", "FEE", "OTHER");
    static final Set<String> PAYMENT_METHODS = Set.of("CARD", "CASH", "OTHER");
    static final Set<String> DECIMAL_SEPARATORS = Set.of(".", ",", "unknown");
    static final Set<String> GROUPING_SEPARATORS = Set.of(".", ",", "space", "'", "none", "unknown");
    static final Set<String> DATE_ORDERS = Set.of("DMY", "MDY", "YMD", "unknown");

    private static final Set<String> TOP = Set.of("documentType", "merchantName", "merchantCountry", "language",
            "purchaseDate", "observedFormats", "currencySymbolOrCode", "taxMode", "total", "handwrittenTotal",
            "subtotal", "taxLines", "lineItems", "payments", "change", "confidence");

    /** Outcome: the parsed extraction, or the list of violations (never both). */
    record Outcome(Optional<DocumentExtraction> extraction, List<String> violations) {
        boolean valid() {
            return extraction.isPresent();
        }
    }

    private final JsonMapper json;

    ExtractionSchemaValidator(JsonMapper json) {
        this.json = json;
    }

    Outcome validate(String raw) {
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (RuntimeException e) {
            return new Outcome(Optional.empty(), List.of("output is not JSON"));
        }
        List<String> errors = new ArrayList<>();
        if (root == null || !root.isObject()) {
            return new Outcome(Optional.empty(), List.of("output is not a JSON object"));
        }
        unknownKeys(root, TOP, "$", errors);
        String documentType = requiredEnum(root, "documentType", DOCUMENT_TYPES, errors);
        String taxMode = requiredEnum(root, "taxMode", TAX_MODES, errors);
        JsonNode formats = root.get("observedFormats");
        DocumentExtraction.ObservedFormats observed = null;
        if (formats == null || !formats.isObject()) {
            errors.add("observedFormats is required and must be an object");
        } else {
            unknownKeys(formats, Set.of("decimalSeparator", "groupingSeparator", "dateOrder"), "observedFormats",
                    errors);
            observed = new DocumentExtraction.ObservedFormats(
                    requiredEnum(formats, "decimalSeparator", DECIMAL_SEPARATORS, errors),
                    requiredEnum(formats, "groupingSeparator", GROUPING_SEPARATORS, errors),
                    requiredEnum(formats, "dateOrder", DATE_ORDERS, errors));
        }
        Optional<String> merchant = text(root, "merchantName", MAX_TEXT, errors);
        Optional<String> country = text(root, "merchantCountry", MAX_VALUE, errors);
        Optional<String> language = text(root, "language", MAX_VALUE, errors);
        Optional<String> date = text(root, "purchaseDate", MAX_VALUE, errors);
        Optional<String> currency = text(root, "currencySymbolOrCode", MAX_VALUE, errors);
        Optional<String> total = text(root, "total", MAX_VALUE, errors);
        Optional<String> handwritten = text(root, "handwrittenTotal", MAX_VALUE, errors);
        Optional<String> subtotal = text(root, "subtotal", MAX_VALUE, errors);
        Optional<String> change = text(root, "change", MAX_VALUE, errors);

        List<DocumentExtraction.TaxLine> taxLines = new ArrayList<>();
        for (JsonNode line : array(root, "taxLines", MAX_LIST, errors)) {
            if (object(line, Set.of("label", "rate", "amount"), "taxLines[]", errors)) {
                taxLines.add(new DocumentExtraction.TaxLine(text(line, "label", MAX_TEXT, errors).orElse(""),
                        text(line, "rate", MAX_VALUE, errors), requiredText(line, "amount", MAX_VALUE, errors)));
            }
        }
        List<DocumentExtraction.LineItem> items = new ArrayList<>();
        for (JsonNode line : array(root, "lineItems", MAX_LINE_ITEMS, errors)) {
            if (object(line, Set.of("type", "description", "quantity", "amount"), "lineItems[]", errors)) {
                items.add(new DocumentExtraction.LineItem(requiredEnum(line, "type", LINE_TYPES, errors),
                        text(line, "description", MAX_TEXT, errors).orElse(""), text(line, "quantity", MAX_VALUE, errors),
                        requiredText(line, "amount", MAX_VALUE, errors)));
            }
        }
        List<DocumentExtraction.Payment> payments = new ArrayList<>();
        for (JsonNode line : array(root, "payments", MAX_LIST, errors)) {
            if (object(line, Set.of("method", "amount"), "payments[]", errors)) {
                payments.add(new DocumentExtraction.Payment(requiredEnum(line, "method", PAYMENT_METHODS, errors),
                        requiredText(line, "amount", MAX_VALUE, errors)));
            }
        }
        DocumentExtraction.Confidence confidence = new DocumentExtraction.Confidence(Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        JsonNode conf = root.get("confidence");
        if (conf != null && !conf.isNull()) {
            if (object(conf, Set.of("merchantName", "purchaseDate", "total", "currency"), "confidence", errors)) {
                confidence = new DocumentExtraction.Confidence(number(conf, "merchantName", errors),
                        number(conf, "purchaseDate", errors), number(conf, "total", errors),
                        number(conf, "currency", errors));
            }
        }
        if (!errors.isEmpty()) {
            return new Outcome(Optional.empty(), List.copyOf(errors));
        }
        return new Outcome(Optional.of(new DocumentExtraction(documentType, merchant, country, language, date,
                observed, currency, taxMode, total, handwritten, subtotal, taxLines, items, payments, change,
                confidence)), List.of());
    }

    private static void unknownKeys(JsonNode node, Set<String> allowed, String where, List<String> errors) {
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (!allowed.contains(field.getKey())) {
                errors.add(where + " has an unknown property " + field.getKey());
            }
        }
    }

    private static boolean object(JsonNode node, Set<String> allowed, String where, List<String> errors) {
        if (node == null || !node.isObject()) {
            errors.add(where + " must be an object");
            return false;
        }
        unknownKeys(node, allowed, where, errors);
        return true;
    }

    private static List<JsonNode> array(JsonNode node, String field, int max, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            errors.add(field + " must be an array");
            return List.of();
        }
        if (value.size() > max) {
            errors.add(field + " has more than " + max + " elements");
            return List.of();
        }
        List<JsonNode> elements = new ArrayList<>();
        value.forEach(elements::add);
        return elements;
    }

    private static Optional<String> text(JsonNode node, String field, int max, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isString()) {
            errors.add(field + " must be a string or null");
            return Optional.empty();
        }
        String text = value.asString();
        if (text.length() > max) {
            errors.add(field + " is longer than " + max + " characters");
            return Optional.empty();
        }
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private static String requiredText(JsonNode node, String field, int max, List<String> errors) {
        Optional<String> value = text(node, field, max, errors);
        if (value.isEmpty() && !(node.get(field) != null && node.get(field).isString())) {
            errors.add(field + " is required");
        }
        return value.orElse("");
    }

    private static String requiredEnum(JsonNode node, String field, Set<String> allowed, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || !allowed.contains(value.asString())) {
            errors.add(field + " must be one of " + allowed);
            return "";
        }
        return value.asString();
    }

    private static Optional<BigDecimal> number(JsonNode node, String field, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isNumber()) {
            errors.add("confidence." + field + " must be a number");
            return Optional.empty();
        }
        BigDecimal confidence = value.decimalValue();
        if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            errors.add("confidence." + field + " must be between 0 and 1");
            return Optional.empty();
        }
        return Optional.of(confidence);
    }
}
