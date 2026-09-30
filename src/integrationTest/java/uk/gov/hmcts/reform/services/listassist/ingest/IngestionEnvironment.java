package uk.gov.hmcts.reform.services.listassist.ingest;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import uk.gov.hmcts.reform.services.listassist.fixtures.AzuriteEmulator;
import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureGenerator.GeneratedScenario;
import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureSeeder;

/**
 * A fresh Azurite and PostgreSQL per test class, so the real container names never collide between classes.
 */
final class IngestionEnvironment implements AutoCloseable {

    private final AzuriteEmulator azurite;
    private final PostgreSQLContainer postgres;

    private IngestionEnvironment(AzuriteEmulator azurite, PostgreSQLContainer postgres) {
        this.azurite = azurite;
        this.postgres = postgres;
    }

    static IngestionEnvironment start() {
        PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
        postgres.start();
        return new IngestionEnvironment(AzuriteEmulator.start(), postgres);
    }

    void register(DynamicPropertyRegistry registry) {
        registry.add("listassist.blob.endpoint", azurite::blobEndpoint);
        registry.add("listassist.blob.auth", () -> "emulator");
        AzuriteEmulator.CONTAINER_NAMES.forEach((container, name) ->
            registry.add("listassist.blob.containers." + container.key(), () -> name));
        registry.add("listassist.ingest.enabled", () -> "false");
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    AzuriteEmulator azurite() {
        return azurite;
    }

    void seed(GeneratedScenario generated, int phase) {
        new FixtureSeeder(azurite.client()).seed(generated, phase);
    }

    @Override
    public void close() {
        azurite.close();
        postgres.stop();
    }
}
