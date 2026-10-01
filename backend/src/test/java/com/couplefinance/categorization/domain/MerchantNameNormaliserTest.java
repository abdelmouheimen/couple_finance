package com.couplefinance.categorization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import com.couplefinance.categorization.api.MerchantNames;
import com.couplefinance.categorization.api.NormalisedMerchant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** BR-CAT-07: merchant normalisation is a deterministic, versioned function. */
class MerchantNameNormaliserTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"CARREFOUR|carrefour", "McDo Lyon|mcdo", "ÉCOLE|ecole"})
    void BR_CAT_07_case_folding(String raw, String expected) {
        assertThat(MerchantNameNormaliser.normalise(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"Café Crème|cafe creme", "Straße Müller|strasse muller", "Œuf Ñandú|oeuf nandu"})
    void BR_CAT_07_accent_removal(String raw, String expected) {
        assertThat(MerchantNameNormaliser.normalise(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"FNAC.COM|fnac com", "L'Atelier d'Anne!|l atelier d anne", "A&B  --  Shop|a b shop"})
    void BR_CAT_07_punctuation_removal(String raw, String expected) {
        assertThat(MerchantNameNormaliser.normalise(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Acme SAS|acme", "Acme S.A.R.L.|acme", "Acme GmbH|acme", "Acme Ltd|acme", "Acme Inc.|acme",
        "Acme SA SAS|acme", "SAS|sas", "S.A.|s a"})
    void BR_CAT_07_legal_suffix_removal(String raw, String expected) {
        assertThat(MerchantNameNormaliser.normalise(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Monoprix 1234|monoprix", "Monoprix N° 45|monoprix", "Store 12 Lidl 0042|store 12 lidl",
        "7 Eleven|7 eleven", "1234|1234"})
    void BR_CAT_07_store_number_removal(String raw, String expected) {
        assertThat(MerchantNameNormaliser.normalise(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Boulangerie Paris|boulangerie", "Franprix Paris 75011|franprix", "Pharmacie Aix-en-Provence|pharmacie",
        "Paris|paris", "Paris Paris|paris"})
    void BR_CAT_07_city_removal(String raw, String expected) {
        assertThat(MerchantNameNormaliser.normalise(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "---", "...", "#"})
    void BR_CAT_07_blank_or_information_free_input_gives_empty_result(String raw) {
        assertThat(MerchantNameNormaliser.normalise(raw)).isEmpty();
        NormalisedMerchant result = MerchantNames.normalise(raw);
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.normaliserVersion()).isEqualTo(MerchantNameNormaliser.VERSION);
    }

    @Test
    void BR_CAT_07_api_exposes_key_and_version() {
        NormalisedMerchant result = MerchantNames.normalise("Café Dupont SARL");

        assertThat(result.key()).isEqualTo("cafe dupont");
        assertThat(result.normaliserVersion()).isEqualTo(MerchantNames.NORMALISER_VERSION).isEqualTo(1);
    }

    /**
     * Golden file: any output change for a recorded input fails here. A legitimate output change requires
     * bumping {@link MerchantNameNormaliser#VERSION} and adding a NEW golden file; the golden file of the
     * previous version must not be edited.
     */
    @Test
    void BR_CAT_07_golden_file_of_current_version_matches_output() throws IOException {
        List<String[]> rows = readGolden(MerchantNameNormaliser.VERSION);

        assertThat(rows).isNotEmpty();
        for (String[] row : rows) {
            assertThat(MerchantNameNormaliser.normalise(row[0])).as("input '%s'", row[0]).isEqualTo(row[1]);
        }
    }

    /** Pinned fingerprint of the version-1 reference data. Editing the lists fails this test until VERSION is bumped. */
    private static final String DATA_SHA256_V1 = "d4cc9c9d7bbb6c60514d87e253e8fd5d0cac874cf2b0e106df9e39f97945db6c";

    @Test
    void BR_CAT_07_reference_data_change_requires_a_version_bump() throws Exception {
        String data = String.join("|", MerchantNormaliserData.LEGAL_SUFFIXES) + "#"
                + String.join("|", MerchantNormaliserData.STORE_MARKERS.stream().sorted().toList()) + "#"
                + String.join("|", MerchantNormaliserData.CITIES);
        String actual = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));

        assertThat(MerchantNameNormaliser.VERSION).isEqualTo(1);
        assertThat(actual).as("reference data changed: bump VERSION, add golden file vN and a new pinned hash")
                .isEqualTo(DATA_SHA256_V1);
    }

    @Test
    void BR_CAT_07_golden_files_of_previous_versions_exist() throws IOException {
        for (int v = 1; v <= MerchantNameNormaliser.VERSION; v++) {
            assertThat(readGolden(v)).as("golden file v%d", v).isNotEmpty();
        }
    }

    private static List<String[]> readGolden(int version) throws IOException {
        String name = "/categorization/merchant-normaliser-golden-v" + version + ".tsv";
        try (InputStream in = MerchantNameNormaliserTest.class.getResourceAsStream(name)) {
            assertThat(in).as("golden resource %s", name).isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return content.lines().filter(l -> !l.isEmpty())
                    .map(l -> l.split("\t", -1)).map(p -> new String[] {p[0], p[1]}).toList();
        }
    }
}
