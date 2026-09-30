package uk.gov.hmcts.reform.services.listassist.fixtures;

import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureManifest.Evidence;
import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureManifest.Expected;
import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureManifest.Unresolved;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Accumulates evidence sets and latest-state expectations. Scenarios author them by hand; the verifier uses the same
 * container to rebuild them from decoded rows, so the two can be compared with plain equality.
 */
final class ExpectedBuilder {

    private final Map<String, Set<String>> hearingCases = new LinkedHashMap<>();
    private final Map<String, Set<String>> hearingSessions = new LinkedHashMap<>();
    private final Map<String, Set<String>> hearingLocations = new LinkedHashMap<>();
    private final Map<String, Set<String>> sessionLocations = new LinkedHashMap<>();
    private final Map<String, Set<String>> sessionOfficers = new LinkedHashMap<>();
    private final Map<String, Set<String>> userCodes = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> latestHearings = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> latestSessions = new LinkedHashMap<>();
    private final Set<String> conflicts = new TreeSet<>();
    private final List<Unresolved> unresolved = new ArrayList<>();

    static String hearingKey(String hearingId, String caseId) {
        return hearingId + "|" + (caseId == null ? "" : caseId);
    }

    ExpectedBuilder hearing(String hearingId, String caseReference, String sessionId, String court, String room) {
        add(hearingCases, hearingId, caseReference);
        add(hearingSessions, hearingId, sessionId);
        add(hearingLocations, hearingId, court == null || room == null ? null : court + ":" + room);
        return this;
    }

    ExpectedBuilder session(String sessionId, String court, String room) {
        add(sessionLocations, sessionId, court == null || room == null ? null : court + ":" + room);
        sessionOfficers.computeIfAbsent(sessionId, key -> new TreeSet<>());
        return this;
    }

    ExpectedBuilder sessionOfficer(String sessionId, String officerId) {
        add(sessionOfficers, sessionId, officerId);
        return this;
    }

    ExpectedBuilder user(String userId, String personalCode) {
        add(userCodes, userId, personalCode);
        return this;
    }

    ExpectedBuilder latestHearing(String hearingId, String caseId, Map<String, String> lifecycle) {
        latestHearings.put(hearingKey(hearingId, caseId), Collections.unmodifiableMap(new LinkedHashMap<>(lifecycle)));
        return this;
    }

    ExpectedBuilder latestSession(String sessionId, Map<String, String> lifecycle) {
        latestSessions.put(sessionId, Collections.unmodifiableMap(new LinkedHashMap<>(lifecycle)));
        return this;
    }

    ExpectedBuilder lifecycleConflict(String container, String key, String modified) {
        conflicts.add(Expected.conflict(container, key, modified));
        return this;
    }

    ExpectedBuilder unresolved(String matrixId, String description, String... symbols) {
        unresolved.add(new Unresolved(matrixId, description, List.of(symbols)));
        return this;
    }

    Evidence evidence() {
        return new Evidence(sorted(hearingCases), sorted(hearingSessions), sorted(hearingLocations),
            sorted(sessionLocations), sorted(sessionOfficers), sorted(userCodes));
    }

    Expected build() {
        return new Expected(evidence(), Collections.unmodifiableMap(new LinkedHashMap<>(latestHearings)),
            Collections.unmodifiableMap(new LinkedHashMap<>(latestSessions)), List.copyOf(conflicts),
            List.copyOf(unresolved));
    }

    private static void add(Map<String, Set<String>> map, String key, String value) {
        Set<String> values = map.computeIfAbsent(key, k -> new TreeSet<>());
        if (value != null) {
            values.add(value);
        }
    }

    private static Map<String, List<String>> sorted(Map<String, Set<String>> map) {
        Map<String, List<String>> sorted = new LinkedHashMap<>();
        map.forEach((key, values) -> sorted.put(key, List.copyOf(values)));
        return Collections.unmodifiableMap(sorted);
    }
}
