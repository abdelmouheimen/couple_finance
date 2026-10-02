package com.couplefinance.platform.authz;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpMethod;
import org.yaml.snakeyaml.Yaml;

/** Enumerates every operation of the committed OpenAPI contract, with its effective security requirement. */
public final class OpenApiOperations {

    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "patch", "head", "options");

    private OpenApiOperations() {
    }

    public static Path defaultSpec() {
        return Path.of(System.getProperty("openapi.spec", "../api/openapi.yaml"));
    }

    public static List<ApiOperation> load(Path spec) {
        try (Reader reader = Files.newBufferedReader(spec, StandardCharsets.UTF_8)) {
            Map<String, Object> root = new Yaml().load(reader);
            return parse(root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + spec, e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<ApiOperation> parse(Map<String, Object> root) {
        Object globalSecurity = root.get("security");
        Map<String, Object> paths = (Map<String, Object>) root.get("paths");
        List<ApiOperation> operations = new ArrayList<>();
        for (Map.Entry<String, Object> path : paths.entrySet()) {
            Map<String, Object> item = (Map<String, Object>) path.getValue();
            for (String method : METHODS) {
                Map<String, Object> operation = (Map<String, Object>) item.get(method);
                if (operation == null) {
                    continue;
                }
                Object security = operation.containsKey("security") ? operation.get("security") : globalSecurity;
                boolean secured = security instanceof List<?> list && !list.isEmpty();
                String id = (String) operation.get("operationId");
                if (id == null) {
                    id = method.toUpperCase(Locale.ROOT) + " " + path.getKey();
                }
                operations.add(new ApiOperation(id, HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)),
                        path.getKey(), secured));
            }
        }
        return operations;
    }
}
