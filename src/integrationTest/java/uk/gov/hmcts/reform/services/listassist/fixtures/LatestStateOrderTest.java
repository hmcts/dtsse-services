package uk.gov.hmcts.reform.services.listassist.fixtures;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureVerifier.ObjectKey;
import uk.gov.hmcts.reform.services.listassist.fixtures.FixtureVerifier.Report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Latest-state selection and conflict detection must not depend on the order rows or files are observed in.
 */
class LatestStateOrderTest {

    private static final String OLDER = "2040-03-11 08:00:00.0000000";
    private static final String NEWER = "2040-03-12 08:00:00.0000000";

    @Test
    void newestTimestampConflictIsReportedAndLeavesNoLatestStateInEveryOrder() {
        List<Map<String, String>> rows = List.of(
            row("h1", OLDER, SyntheticRows.listedHearing()),
            row("h1", NEWER, SyntheticRows.listedHearing()),
            row("h1", NEWER, SyntheticRows.cancelledHearing(OLDER)));
        String conflict = FixtureManifest.Expected.conflict(BaselineScenario.HEARINGS,
            ExpectedBuilder.hearingKey("h1", "c1"), NEWER);

        for (List<Map<String, String>> ordering : permutations(rows)) {
            Report singleFile = report(List.of(ordering));
            Report fileEach = report(ordering.stream().map(List::of).toList());
            for (Report report : List.of(singleFile, fileEach)) {
                assertThat(report.lifecycleConflicts()).as("order %s", ordering).containsExactly(conflict);
                assertThat(report.latestHearingLifecycle()).as("order %s", ordering).isEmpty();
            }
        }
    }

    @Test
    void olderTimestampConflictIsReportedButNewerAgreedStateStillWins() {
        List<Map<String, String>> rows = List.of(
            row("h1", OLDER, SyntheticRows.listedHearing()),
            row("h1", OLDER, SyntheticRows.cancelledHearing(OLDER)),
            row("h1", NEWER, SyntheticRows.heardHearing()));

        for (List<Map<String, String>> ordering : permutations(rows)) {
            Report report = report(ordering.stream().map(List::of).toList());
            assertThat(report.lifecycleConflicts()).as("order %s", ordering).containsExactly(
                FixtureManifest.Expected.conflict(BaselineScenario.HEARINGS, ExpectedBuilder.hearingKey("h1", "c1"),
                    OLDER));
            assertThat(report.latestHearingLifecycle()).as("order %s", ordering)
                .containsExactly(Map.entry(ExpectedBuilder.hearingKey("h1", "c1"), SyntheticRows.heardHearing()));
        }
    }

    private static Map<String, String> row(String hearing, String modified, Map<String, String> lifecycle) {
        Map<String, String> row = new LinkedHashMap<>(lifecycle);
        row.put("ID_Hearing", hearing);
        row.put("ID_Case", "c1");
        row.put("Last_Modified_Date", modified);
        return row;
    }

    private static Report report(List<List<Map<String, String>>> files) {
        Map<ObjectKey, List<Map<String, String>>> observed = new LinkedHashMap<>();
        for (int i = 0; i < files.size(); i++) {
            observed.put(new ObjectKey(BaselineScenario.HEARINGS, "file-" + i, "sha-" + i), files.get(i));
        }
        return new Report(observed);
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.isEmpty()) {
            return List.of(List.of());
        }
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T head = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> ordering = new ArrayList<>();
                ordering.add(head);
                ordering.addAll(tail);
                result.add(ordering);
            }
        }
        return result;
    }
}
