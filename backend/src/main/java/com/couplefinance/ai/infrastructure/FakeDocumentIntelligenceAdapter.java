package com.couplefinance.ai.infrastructure;

import com.couplefinance.ai.api.DocumentExtractionRequest;
import com.couplefinance.ai.api.DocumentExtractionResult;
import com.couplefinance.ai.api.DocumentIntelligencePort;

/**
 * Deterministic adapter for local development and tests (ai.md §3): sends nothing anywhere and answers a fixed,
 * schema-valid transcription through the same pipeline (validation, call log) as a real provider.
 */
final class FakeDocumentIntelligenceAdapter implements DocumentIntelligencePort {

    static final String PROVIDER = "fake";
    static final String MODEL = "fixture-v1";

    /** A small French supermarket receipt, transcribed as printed. */
    static final String FIXTURE = """
            {"documentType":"RECEIPT","merchantName":"Carrefour","merchantCountry":"FR","language":"fr",
             "purchaseDate":"04/10/2026",
             "observedFormats":{"decimalSeparator":",","groupingSeparator":"none","dateOrder":"DMY"},
             "currencySymbolOrCode":"€","taxMode":"INCLUSIVE","total":"13,78","handwrittenTotal":null,
             "subtotal":null,"taxLines":[],
             "lineItems":[{"type":"ITEM","description":"LAIT BIO","quantity":null,"amount":"1,29"},
                          {"type":"ITEM","description":"PAIN","quantity":null,"amount":"1,10"},
                          {"type":"ITEM","description":"POULET","quantity":null,"amount":"8,90"},
                          {"type":"ITEM","description":"TOMATES","quantity":null,"amount":"2,49"}],
             "payments":[{"method":"CARD","amount":"13,78"}],"change":null,
             "confidence":{"merchantName":0.98,"purchaseDate":0.96,"total":0.99,"currency":0.97}}
            """;

    private final ExtractionPipeline pipeline;

    FakeDocumentIntelligenceAdapter(ExtractionPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public DocumentExtractionResult extract(DocumentExtractionRequest request) {
        return pipeline.run(request, new DocumentExtractionResult.Provenance(PROVIDER, MODEL,
                ExtractionPipeline.PROMPT_VERSION), (prompt, pages) -> new ExtractionPipeline.ModelAnswer(FIXTURE,
                        null, null));
    }
}
