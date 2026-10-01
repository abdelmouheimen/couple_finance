package com.couplefinance.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.couplefinance.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The committed contract {@code api/openapi.yaml} must match the spec generated from the code
 * (architecture.md §7). After an intentional API change, regenerate it with {@code ./gradlew updateOpenApi}
 * and commit the result together with the code change.
 */
@IntegrationTest
class OpenApiContractTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void committed_openapi_contract_matches_the_code() throws IOException {
        Path committedSpec = Path.of(System.getProperty("openapi.spec", "../api/openapi.yaml"));
        MvcTestResult result = mvc.get().uri("/v3/api-docs.yaml").exchange();
        assertThat(result).hasStatusOk();
        String generated = normalise(result.getResponse().getContentAsString(StandardCharsets.UTF_8));

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(committedSpec.getParent());
            Files.writeString(committedSpec, generated, StandardCharsets.UTF_8);
            return;
        }

        assertThat(committedSpec)
                .as("api/openapi.yaml is missing. Run ./gradlew updateOpenApi and commit it.")
                .exists();
        assertThat(generated)
                .as("api/openapi.yaml is out of date. Run ./gradlew updateOpenApi and commit it.")
                .isEqualTo(normalise(Files.readString(committedSpec, StandardCharsets.UTF_8)));
    }

    private static String normalise(String yaml) {
        return yaml.replace("\r\n", "\n").strip() + "\n";
    }
}
