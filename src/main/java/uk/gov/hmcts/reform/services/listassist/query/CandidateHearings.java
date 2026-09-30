package uk.gov.hmcts.reform.services.listassist.query;

import java.time.Instant;
import java.util.List;

/**
 * Candidate hearings for one personal code and date, with the diagnostics and ingestion status needed to judge them.
 * There is deliberately no completeness flag: empty diagnostics do not prove the list is complete.
 */
public record CandidateHearings(List<Candidate> candidates, List<Diagnostic> diagnostics,
                                List<ContainerStatus> containers) {

    /**
     * One hearing/case/session association. {@code basis} says which evidence matched: {@code session},
     * {@code hearing_slot} or {@code both}. It is a candidate, never a confirmed assignment.
     */
    public record Candidate(
        String idHearing, String idCase, String caseNo, String idSession, String idBooking,
        String hearingDate, String hearingDateTime, String startTime24h, String duration,
        String hearingType, String channel,
        String cdLocality, String locality, String cdLocation, String location, String cdJurisdiction,
        String listingStatusCode, String listingStatus, String heardFlag,
        String basis,
        List<String> matchedUserIds,
        List<SessionOfficer> sessionOfficers,
        List<String> hearingSlotOfficers,
        String sessionState
    ) {
    }

    public record SessionOfficer(String idJo, String presiding, String idBooking) {
    }

    /**
     * {@code sampleIds} holds at most ten source identifiers, never row values.
     */
    public record Diagnostic(String code, int count, List<String> sampleIds) {
    }

    public record ContainerStatus(String container, boolean bootstrapped, int failedFiles,
                                  Instant lastSuccessfulIngestion, String newestIngestedFileTimestamp) {
    }
}
