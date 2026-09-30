package uk.gov.hmcts.reform.services.listassist.fixtures;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Observed physical column profiles, loaded from the schema inventory resource rather than a copied column list.
 */
public final class ListAssistSchemaInventory {

    public static final String RESOURCE = "/listassist/parquet-schemas.json";

    private final Map<String, DatasetSchema> observed;

    private ListAssistSchemaInventory(Map<String, DatasetSchema> observed) {
        this.observed = observed;
    }

    public static ListAssistSchemaInventory load() {
        try (InputStream in = ListAssistSchemaInventory.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Schema inventory not on classpath: " + RESOURCE);
            }
            JsonNode root = JsonMapper.shared().readTree(in);
            requireStringPhysicalType(root.path("observedPhysicalFieldType"));
            Map<String, DatasetSchema> observed = new LinkedHashMap<>();
            for (JsonNode dataset : root.path("datasets")) {
                JsonNode profiles = dataset.path("columnProfiles");
                if (profiles.isEmpty()) {
                    continue;
                }
                if (profiles.size() != 1) {
                    throw new IllegalStateException("Expected one column profile for " + dataset.path("container"));
                }
                List<String> columns = new ArrayList<>();
                profiles.get(0).forEach(column -> columns.add(column.asString()));
                DatasetSchema schema = new DatasetSchema(
                    dataset.path("container").asString(),
                    dataset.path("viewToken").asString(),
                    dataset.path("scope").asString(),
                    List.copyOf(columns)
                );
                observed.put(schema.container(), schema);
            }
            return new ListAssistSchemaInventory(Map.copyOf(observed));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public DatasetSchema observed(String container) {
        DatasetSchema schema = observed.get(container);
        if (schema == null) {
            throw new IllegalArgumentException("No observed column profile for container " + container);
        }
        return schema;
    }

    private static void requireStringPhysicalType(JsonNode type) {
        if (!"BYTE_ARRAY".equals(type.path("type").asString())
            || !"OPTIONAL".equals(type.path("repetition").asString())
            || !"STRING".equals(type.path("logicalType").asString())) {
            throw new IllegalStateException("Inventory physical type is no longer nullable UTF-8 string: " + type);
        }
    }

    public record DatasetSchema(String container, String viewToken, String scope, List<String> columns) {
    }
}
