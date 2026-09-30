package uk.gov.hmcts.reform.services.listassist.fixtures;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * External test oracle written next to the generated Blobs. It is never uploaded to a source container, so the
 * application can only discover objects through Blob listing.
 */
public record FixtureManifest(
    int fixtureFormatVersion,
    String scenarioId,
    long seed,
    String businessClock,
    String evidence,
    String emulatorImage,
    Map<String, String> generator,
    Map<String, String> symbols,
    List<ObjectEntry> objects,
    Expected expected,
    List<String> privacySentinels
) {

    public static final int FORMAT_VERSION = 2;

    /**
     * Objects stored in the emulator once every phase up to {@code phase} has been seeded: a planned overwrite
     * replaces the earlier version of the same Blob name.
     */
    public List<ObjectEntry> storedAfterPhase(int phase) {
        Map<String, ObjectEntry> stored = new LinkedHashMap<>();
        objects.stream()
            .filter(object -> object.phase() <= phase)
            .forEach(object -> stored.put(object.container() + "/" + object.blobName(), object));
        return List.copyOf(stored.values());
    }

    public int lastPhase() {
        return objects.stream().mapToInt(ObjectEntry::phase).max().orElse(0);
    }

    public record ObjectEntry(
        String container,
        String blobName,
        String extractKind,
        String schemaProfile,
        int phase,
        String upload,
        int rowCount,
        long contentLength,
        String sha256
    ) {
    }

    /**
     * Authored expectations, keyed by invented source IDs. {@code evidence} holds every value ever delivered, never a
     * chosen truth. The latest-lifecycle maps apply the policy in {@link #LATEST_POLICY}. {@code lifecycleConflicts}
     * lists every {@code container|key@timestamp} whose rows disagree; a key conflicted at its newest timestamp has no
     * latest state. Anything whose domain rule is not agreed is listed in {@code unresolved}.
     */
    public record Expected(
        Evidence evidence,
        Map<String, Map<String, String>> latestHearingLifecycle,
        Map<String, Map<String, String>> latestSessionLifecycle,
        List<String> lifecycleConflicts,
        List<Unresolved> unresolved
    ) {
        public static final String LATEST_POLICY =
            "row with greatest Last_Modified value per key wins; a later null replaces an earlier value; "
                + "disagreeing rows at the greatest value leave the key without a latest state";

        public static String conflict(String container, String key, String modified) {
            return container + "|" + key + "@" + modified;
        }
    }

    /**
     * Every association observed across all delivered versions. Location pairs are {@code court:room} codes: hearing
     * {@code CD_Locality:CD_Location} and session {@code CD_Court:CD_Room}.
     */
    public record Evidence(
        Map<String, List<String>> hearingCaseReferences,
        Map<String, List<String>> hearingSessions,
        Map<String, List<String>> hearingLocations,
        Map<String, List<String>> sessionLocations,
        Map<String, List<String>> sessionOfficers,
        Map<String, List<String>> userPersonalCodes
    ) {
    }

    public record Unresolved(String matrixId, String description, List<String> symbols) {
    }
}
