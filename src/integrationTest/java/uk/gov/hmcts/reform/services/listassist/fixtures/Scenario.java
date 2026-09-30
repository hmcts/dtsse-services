package uk.gov.hmcts.reform.services.listassist.fixtures;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * An authored fixture world. {@code symbols} maps scenario symbols such as {@code officer_a} to the invented source
 * values written into Parquet; {@code expected} is the oracle, kept outside the source containers.
 */
public record Scenario(String id, long seed, String evidence, LocalDate businessClock, Map<String, String> symbols,
                       List<Delivery> deliveries, FixtureManifest.Expected expected, List<String> privacySentinels) {
}
