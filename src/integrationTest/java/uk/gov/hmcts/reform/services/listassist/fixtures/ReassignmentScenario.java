package uk.gov.hmcts.reform.services.listassist.fixtures;

import uk.gov.hmcts.reform.services.listassist.fixtures.Delivery.ExtractKind;
import uk.gov.hmcts.reform.services.listassist.fixtures.Delivery.UploadAction;
import uk.gov.hmcts.reform.services.listassist.fixtures.SyntheticRows.CaseRef;
import uk.gov.hmcts.reform.services.listassist.fixtures.SyntheticRows.HearingDef;
import uk.gov.hmcts.reform.services.listassist.fixtures.SyntheticRows.Officer;
import uk.gov.hmcts.reform.services.listassist.fixtures.SyntheticRows.Room;
import uk.gov.hmcts.reform.services.listassist.fixtures.SyntheticRows.Seat;
import uk.gov.hmcts.reform.services.listassist.fixtures.SyntheticRows.SessionDef;
import uk.gov.hmcts.reform.services.listassist.fixtures.SyntheticRows.Venue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static uk.gov.hmcts.reform.services.listassist.fixtures.BaselineScenario.HEARINGS;
import static uk.gov.hmcts.reform.services.listassist.fixtures.BaselineScenario.SESSIONS;
import static uk.gov.hmcts.reform.services.listassist.fixtures.BaselineScenario.SESSION_OFFICERS;
import static uk.gov.hmcts.reform.services.listassist.fixtures.BaselineScenario.USER;

/**
 * Progressive changes across three phases: a hearing moving session (I10) versus one spanning two days, a
 * cancellation later reinstated (L01/L02) followed by a stale cancelled row arriving last (D03), the
 * presiding/placeholder/non-presiding officer sequence (I02), hearing-level officer IDs lagging session evidence (I04),
 * a cancelled session under an active hearing (L03), a personal-code change (I08), a heard hearing that is not
 * cancelled (L04), two disagreeing rows with the same modification timestamp in different files (D04) and a
 * corrected file overwriting an earlier Blob name (B08).
 *
 * <p>Expectations are written out by hand below rather than derived from the rows.
 */
public final class ReassignmentScenario {

    public static final String ID = "reassignment";

    static final String CANCELLED_AT = "2040-03-11 09:14:00.0000000";
    static final String SESSION_CANCELLED_AT = "2040-03-11 07:40:12.0000000";
    static final String CONFLICT_MODIFIED = "2040-03-12 08:00:00.0000000";

    private static final LocalDate DAY_ONE = LocalDate.of(2040, 3, 12);
    private static final LocalDate DAY_TWO = LocalDate.of(2040, 3, 13);
    private static final LocalDate DAY_THREE = LocalDate.of(2040, 3, 14);
    private static final LocalDateTime FULL_EXTRACT = LocalDateTime.of(2040, 3, 10, 3, 0);
    private static final LocalDateTime PHASE_TWO = LocalDateTime.of(2040, 3, 11, 11, 0);
    private static final LocalDateTime PHASE_THREE = LocalDateTime.of(2040, 3, 12, 11, 0);

    private final ListAssistSchemaInventory inventory;
    private final long seed;
    private final SyntheticRows rows;

    private ReassignmentScenario(ListAssistSchemaInventory inventory, long seed) {
        this.inventory = inventory;
        this.seed = seed;
        this.rows = new SyntheticRows(new SyntheticIds(seed));
    }

    public static Scenario build(ListAssistSchemaInventory inventory, long seed) {
        return new ReassignmentScenario(inventory, seed).build();
    }

    private Scenario build() {
        Venue east = new Venue("venue_east", "Synthetic Venue East");
        Room east1 = new Room("room_east_1", east, "Synthetic East Courtroom 1");
        Room east2 = new Room("room_east_2", east, "Synthetic East Courtroom 2");

        Officer officerOld = new Officer("officer_old", "Old", false, true);
        Officer officerNew = new Officer("officer_new", "New", false, true);
        Officer placeholder = new Officer("officer_placeholder", "Generic", false, false);

        SessionDef sessionR1 = session("session_r1", east1, DAY_ONE, new Seat(officerOld, true, "booking_r1"));
        SessionDef sessionR2 = session("session_r2", east2, DAY_ONE, new Seat(officerNew, true, "booking_r2"));
        SessionDef sessionR3 = session("session_r3", east2, DAY_TWO, new Seat(officerOld, true, null));
        SessionDef sessionM1 = session("session_m1", east1, DAY_TWO, new Seat(officerNew, true, null));
        SessionDef sessionM2 = session("session_m2", east1, DAY_THREE, new Seat(officerNew, true, null));

        CaseRef caseMove = new CaseRef("case_move", "RA-CASE-MOVE");
        CaseRef caseCancel = new CaseRef("case_cancel", "RA-CASE-CANCEL");
        CaseRef caseMulti = new CaseRef("case_multi", "RA-CASE-MULTI");
        CaseRef caseSessionCancelled = new CaseRef("case_session_cancelled", "RA-CASE-SESSION-CANCELLED");
        CaseRef caseHeard = new CaseRef("case_heard", "RA-CASE-HEARD");
        CaseRef caseConflict = new CaseRef("case_conflict", "RA-CASE-CONFLICT");

        HearingDef moveOnR1 = hearing("hearing_move", sessionR1, LocalTime.of(10, 0), 60, caseMove);
        HearingDef moveOnR2 = hearing("hearing_move", sessionR2, LocalTime.of(10, 0), 60, caseMove);
        HearingDef cancel = hearing("hearing_cancel", sessionR1, LocalTime.of(11, 30), 30, caseCancel);
        HearingDef multiDayOne = hearing("hearing_multi_day", sessionM1, LocalTime.of(10, 0), 360, caseMulti);
        HearingDef multiDayTwo = hearing("hearing_multi_day", sessionM2, LocalTime.of(10, 0), 360, caseMulti);
        HearingDef underCancelledSession = hearing("hearing_session_cancelled", sessionR3, LocalTime.of(10, 0), 60,
            caseSessionCancelled);
        HearingDef heard = hearing("hearing_heard", sessionR2, LocalTime.of(11, 30), 30, caseHeard);
        HearingDef conflict = hearing("hearing_conflict", sessionR2, LocalTime.of(14, 0), 30, caseConflict);

        List<Delivery> deliveries = new ArrayList<>();

        // Phase 1: Full of every container.
        LocalDateTime beforeFull = FULL_EXTRACT.minusDays(1);
        deliveries.add(delivery(USER, ExtractKind.FULL, FULL_EXTRACT.plusSeconds(11), 1, UploadAction.CREATE,
            List.of(rows.user(officerOld, beforeFull), rows.user(officerNew, beforeFull),
                rows.user(placeholder, beforeFull))));
        deliveries.add(delivery(SESSIONS, ExtractKind.FULL, FULL_EXTRACT.plusSeconds(47), 1, UploadAction.CREATE,
            List.of(sessionR1, sessionR2, sessionR3, sessionM1, sessionM2).stream()
                .map(session -> rows.session(session, beforeFull)).toList()));
        deliveries.add(delivery(SESSION_OFFICERS, ExtractKind.FULL, FULL_EXTRACT.plusSeconds(93), 1,
            UploadAction.CREATE, List.of(sessionR1, sessionR2, sessionR3, sessionM1, sessionM2).stream()
                .flatMap(session -> rows.seats(session, beforeFull).stream()).toList()));
        deliveries.add(delivery(HEARINGS, ExtractKind.FULL, FULL_EXTRACT, 1, UploadAction.CREATE,
            List.of(moveOnR1, cancel, multiDayOne, multiDayTwo, underCancelledSession, heard, conflict).stream()
                .flatMap(hearing -> rows.hearingRows(hearing, beforeFull).stream()).toList()));

        // Phase 2: the move, the cancellation, a placeholder seat and a cancelled session.
        Map<String, String> moved = rows.hearing(moveOnR2, caseMove, PHASE_TWO.minusHours(3));
        moved.put("Hearing_Duration", "-30");
        Map<String, String> cancelled = rows.hearing(cancel, caseCancel, PHASE_TWO.minusMinutes(100));
        cancelled.putAll(SyntheticRows.cancelledHearing(CANCELLED_AT));
        deliveries.add(delivery(HEARINGS, ExtractKind.INCR, PHASE_TWO, 2, UploadAction.CREATE,
            List.of(moved, cancelled)));
        deliveries.add(delivery(SESSION_OFFICERS, ExtractKind.INCR, PHASE_TWO.plusSeconds(58), 2,
            UploadAction.CREATE, List.of(rows.seat(sessionR1, new Seat(placeholder, false, null),
                PHASE_TWO.minusHours(2)))));
        Map<String, String> sessionCancelled = rows.session(sessionR3, PHASE_TWO.minusMinutes(195));
        sessionCancelled.putAll(SyntheticRows.cancelledSession(SESSION_CANCELLED_AT));
        deliveries.add(delivery(SESSIONS, ExtractKind.INCR, PHASE_TWO.plusSeconds(31), 2, UploadAction.CREATE,
            List.of(sessionCancelled)));

        // Phase 3: reinstatement, a later non-presiding seat, a changed personal code and a corrected phase 2 file.
        Map<String, String> reinstated = rows.hearing(cancel, caseCancel, PHASE_THREE.minusHours(2));
        Map<String, String> wasHeard = rows.hearing(heard, caseHeard, PHASE_THREE.minusMinutes(50));
        wasHeard.put("Heard_Flag", SyntheticRows.HEARD);
        Map<String, String> conflictListed = rows.hearing(conflict, caseConflict, PHASE_THREE);
        conflictListed.put("Last_Modified_Date", CONFLICT_MODIFIED);
        deliveries.add(delivery(HEARINGS, ExtractKind.INCR, PHASE_THREE, 3, UploadAction.CREATE,
            List.of(reinstated, wasHeard, conflictListed)));
        deliveries.add(delivery(SESSION_OFFICERS, ExtractKind.INCR, PHASE_THREE.plusSeconds(264), 3,
            UploadAction.CREATE, List.of(rows.seat(sessionR1, new Seat(officerNew, false, "booking_r1_new"),
                PHASE_THREE.minusHours(3)))));
        Map<String, String> newCode = rows.user(officerOld, PHASE_THREE.minusHours(9));
        newCode.put("External_User_ID", rows.replacementPersonalCode(officerOld));
        deliveries.add(delivery(USER, ExtractKind.INCR, PHASE_THREE.minusHours(8).minusMinutes(30), 3,
            UploadAction.CREATE, List.of(newCode)));
        Map<String, String> correctedMove = new LinkedHashMap<>(moved);
        correctedMove.put("Hearing_Duration", "30");
        deliveries.add(delivery(HEARINGS, ExtractKind.INCR, PHASE_TWO, 3, UploadAction.OVERWRITE,
            List.of(correctedMove, new LinkedHashMap<>(cancelled))));
        // Delivered last, but older than both the cancellation and the reinstatement.
        Map<String, String> stale = rows.hearing(cancel, caseCancel, PHASE_TWO.minusMinutes(110));
        stale.putAll(SyntheticRows.cancelledHearing(CANCELLED_AT));
        // Same key and Last_Modified_Date as conflictListed, but cancelled.
        Map<String, String> conflictCancelled = rows.hearing(conflict, caseConflict, PHASE_THREE);
        conflictCancelled.putAll(SyntheticRows.cancelledHearing(CANCELLED_AT));
        conflictCancelled.put("Last_Modified_Date", CONFLICT_MODIFIED);
        deliveries.add(delivery(HEARINGS, ExtractKind.INCR, PHASE_THREE.plusMinutes(30), 3, UploadAction.CREATE,
            List.of(stale, conflictCancelled)));

        return new Scenario(ID, seed, "robustness-and-unresolved", DAY_ONE, rows.symbols(), List.copyOf(deliveries),
            expected(), rows.sentinels());
    }

    private FixtureManifest.Expected expected() {
        String east = id("venue_east");
        String room1 = id("room_east_1");
        String room2 = id("room_east_2");
        String officerOld = id("officer_old");
        String officerNew = id("officer_new");
        String placeholder = id("officer_placeholder");
        return new ExpectedBuilder()
            .hearing(id("hearing_move"), "RA-CASE-MOVE", id("session_r1"), east, room1)
            .hearing(id("hearing_move"), "RA-CASE-MOVE", id("session_r2"), east, room2)
            .hearing(id("hearing_cancel"), "RA-CASE-CANCEL", id("session_r1"), east, room1)
            .hearing(id("hearing_multi_day"), "RA-CASE-MULTI", id("session_m1"), east, room1)
            .hearing(id("hearing_multi_day"), "RA-CASE-MULTI", id("session_m2"), east, room1)
            .hearing(id("hearing_session_cancelled"), "RA-CASE-SESSION-CANCELLED", id("session_r3"), east, room2)
            .hearing(id("hearing_heard"), "RA-CASE-HEARD", id("session_r2"), east, room2)
            .hearing(id("hearing_conflict"), "RA-CASE-CONFLICT", id("session_r2"), east, room2)
            .session(id("session_r1"), east, room1)
            .session(id("session_r2"), east, room2)
            .session(id("session_r3"), east, room2)
            .session(id("session_m1"), east, room1)
            .session(id("session_m2"), east, room1)
            .sessionOfficer(id("session_r1"), officerOld)
            .sessionOfficer(id("session_r1"), placeholder)
            .sessionOfficer(id("session_r1"), officerNew)
            .sessionOfficer(id("session_r2"), officerNew)
            .sessionOfficer(id("session_r3"), officerOld)
            .sessionOfficer(id("session_m1"), officerNew)
            .sessionOfficer(id("session_m2"), officerNew)
            .user(officerOld, id("officer_old_personal_code"))
            .user(officerOld, id("officer_old_personal_code_replacement"))
            .user(officerNew, id("officer_new_personal_code"))
            .user(placeholder, null)
            .latestHearing(id("hearing_move"), id("case_move"), SyntheticRows.listedHearing())
            .latestHearing(id("hearing_cancel"), id("case_cancel"), SyntheticRows.listedHearing())
            .latestHearing(id("hearing_multi_day"), id("case_multi"), SyntheticRows.listedHearing())
            .latestHearing(id("hearing_session_cancelled"), id("case_session_cancelled"),
                SyntheticRows.listedHearing())
            .latestHearing(id("hearing_heard"), id("case_heard"), SyntheticRows.heardHearing())
            .lifecycleConflict(HEARINGS, ExpectedBuilder.hearingKey(id("hearing_conflict"), id("case_conflict")),
                CONFLICT_MODIFIED)
            .latestSession(id("session_r1"), SyntheticRows.releasedSession())
            .latestSession(id("session_r2"), SyntheticRows.releasedSession())
            .latestSession(id("session_r3"), SyntheticRows.cancelledSession(SESSION_CANCELLED_AT))
            .latestSession(id("session_m1"), SyntheticRows.releasedSession())
            .latestSession(id("session_m2"), SyntheticRows.releasedSession())
            .unresolved("I02", "Presiding officer, then a generic placeholder, then a later non-presiding officer on "
                + "one session with null inactive dates; no current officer is chosen",
                "session_r1", "officer_old", "officer_placeholder", "officer_new")
            .unresolved("I04", "Hearing-level ID_JO_1 keeps the original officer after later session seat evidence",
                "hearing_cancel", "session_r1")
            .unresolved("I10", "One hearing moves session while another spans two sessions on different days; the "
                + "both associations stay visible", "hearing_move", "hearing_multi_day")
            .unresolved("L03", "Session cancelled while its hearing remains listed; hearing outcome not inferred",
                "session_r3", "hearing_session_cancelled")
            .unresolved("D04", "Listed and cancelled rows share a Last_Modified_Date; the conflict is recorded and the "
                + "hearing has no latest lifecycle state", "hearing_conflict")
            .unresolved("I08", "A user's personal code changes; only the current code matches",
                "officer_old")
            .build();
    }

    private String id(String symbol) {
        String id = rows.symbols().get(symbol);
        if (id == null) {
            throw new IllegalStateException("Expectation refers to unallocated symbol " + symbol);
        }
        return id;
    }

    private static SessionDef session(String symbol, Room room, LocalDate date, Seat seat) {
        return new SessionDef(symbol, room, date, LocalTime.of(10, 0), LocalTime.of(16, 0), List.of(seat));
    }

    private static HearingDef hearing(String symbol, SessionDef session, LocalTime start, int duration,
                                      CaseRef caseRef) {
        return new HearingDef(symbol, session, session.date(), start, duration, "Synthetic Directions",
            List.of(caseRef), "Synthetic In Person");
    }

    private Delivery delivery(String container, ExtractKind kind, LocalDateTime timestamp, int phase,
                              UploadAction upload, List<Map<String, String>> rows) {
        return new Delivery(inventory.observed(container), kind, timestamp.toLocalDate(), timestamp, phase, upload,
            rows);
    }
}
