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
import java.util.List;
import java.util.Map;

/**
 * Small, coherent, entirely invented ListAssist world across the four core containers.
 *
 * <p>Phase 1 is a Full of every container on 2040-01-30 plus a same-day hearings Incr. Phase 2 adds a new session,
 * officer seat and hearing, with an empty user Incr. Phase 3 delivers that officer's personal-code mapping under the
 * next month's prefix, after the assignment it resolves.
 */
public final class BaselineScenario {

    public static final String ID = "baseline";
    public static final LocalDate BUSINESS_CLOCK = LocalDate.of(2040, 1, 31);

    static final String HEARINGS = "hearings";
    static final String SESSIONS = "sessions";
    static final String SESSION_OFFICERS = "session-officers";
    static final String USER = "users";

    private static final LocalDate DAY_ONE = LocalDate.of(2040, 1, 31);
    private static final LocalDate DAY_TWO = LocalDate.of(2040, 2, 1);
    private static final LocalDateTime FULL_EXTRACT = LocalDateTime.of(2040, 1, 30, 3, 0);

    private final ListAssistSchemaInventory inventory;
    private final long seed;
    private final SyntheticRows rows;

    private BaselineScenario(ListAssistSchemaInventory inventory, long seed) {
        this.inventory = inventory;
        this.seed = seed;
        this.rows = new SyntheticRows(new SyntheticIds(seed));
    }

    public static Scenario build(ListAssistSchemaInventory inventory, long seed) {
        return new BaselineScenario(inventory, seed).build();
    }

    private Scenario build() {
        Venue north = new Venue("venue_north", "Synthetic Venue North");
        Venue south = new Venue("venue_south", "Synthetic Venue South");
        Room north1 = new Room("room_north_1", north, "Synthetic North Courtroom 1");
        Room north2 = new Room("room_north_2", north, "Synthetic North Courtroom 2");
        Room south1 = new Room("room_south_1", south, "Synthetic South Courtroom 1");

        Officer officerA = new Officer("officer_a", "A", true, true);
        Officer officerB = new Officer("officer_b", "B", false, true);
        Officer officerC = new Officer("officer_c", "C", false, true);
        Officer officerD = new Officer("officer_d", "D", false, true);
        Officer placeholder = new Officer("officer_placeholder", "Placeholder", false, false);

        SessionDef sessionA = new SessionDef("session_a", north1, DAY_ONE, LocalTime.of(10, 0), LocalTime.of(13, 0),
            List.of(new Seat(officerA, true, "booking_a")));
        SessionDef sessionB = new SessionDef("session_b", north2, DAY_ONE, LocalTime.of(10, 0), LocalTime.of(16, 0),
            List.of(new Seat(officerB, true, "booking_b1"), new Seat(officerC, false, null)));
        SessionDef sessionC = new SessionDef("session_c", south1, DAY_ONE, LocalTime.of(14, 0), LocalTime.of(16, 30),
            List.of(new Seat(placeholder, true, null)));
        SessionDef sessionD = new SessionDef("session_d", south1, DAY_TWO, LocalTime.of(14, 0), LocalTime.of(16, 0),
            List.of(new Seat(officerD, true, "booking_d")));
        SessionDef sessionE = new SessionDef("session_e", north1, DAY_TWO, LocalTime.of(10, 0), LocalTime.of(12, 0),
            List.of(new Seat(officerA, true, "booking_e")));

        CaseRef caseA = new CaseRef("case_a", "TEST-CASE-A");
        CaseRef caseB = new CaseRef("case_b", "TEST-CASE-B");
        CaseRef caseC = new CaseRef("case_c", "TEST-000123/2040");
        CaseRef caseD = new CaseRef("case_d", "TEST-40000123");
        CaseRef caseE = new CaseRef("case_e", "TEST-TC/2040/0007");
        CaseRef caseF = new CaseRef("case_f", "TEST-CASE-F");
        CaseRef caseG = new CaseRef("case_g", "TEST-CASE-G");

        // hearing_a and hearing_b share a start time; hearing_a has two cases; case_a has several hearings.
        HearingDef hearingA = new HearingDef("hearing_a", sessionA, DAY_ONE, LocalTime.of(10, 0), 60,
            "Synthetic Directions", List.of(caseA, caseB), "Synthetic In Person");
        HearingDef hearingB = new HearingDef("hearing_b", sessionA, DAY_ONE, LocalTime.of(10, 0), 30,
            "Synthetic Application", List.of(caseC), "Synthetic In Person");
        HearingDef hearingC = new HearingDef("hearing_c", sessionA, DAY_ONE, LocalTime.of(12, 45), 15,
            "Synthetic Review", List.of(caseD), "Synthetic Video");
        HearingDef readingTime = new HearingDef("hearing_reading_time", sessionB, DAY_ONE, LocalTime.of(10, 0), 60,
            "Synthetic Reading Time", List.of(), "Synthetic In Person");
        HearingDef hearingE = new HearingDef("hearing_e", sessionB, DAY_ONE, LocalTime.of(11, 30), 90,
            "Synthetic Final Hearing", List.of(caseA), "Synthetic In Person");
        HearingDef unassigned = new HearingDef("hearing_unassigned", null, DAY_TWO, null, 45,
            "Synthetic Directions", List.of(caseE), null);
        HearingDef hearingG = new HearingDef("hearing_g", sessionE, DAY_TWO, LocalTime.of(10, 0), 60,
            "Synthetic Directions", List.of(caseF), "Synthetic In Person");
        final HearingDef hearingH = new HearingDef("hearing_h", sessionD, DAY_TWO, LocalTime.of(14, 0), 60,
            "Synthetic Application", List.of(caseG), "Synthetic Telephone");

        List<Delivery> deliveries = new ArrayList<>();
        LocalDateTime beforeFull = FULL_EXTRACT.minusDays(1);

        // Phase 1: Full extracts with independent timestamps, then a same-day hearings Incr.
        deliveries.add(delivery(USER, ExtractKind.FULL, FULL_EXTRACT.plusSeconds(19), 1,
            users(List.of(officerA, officerB, officerC, placeholder), beforeFull)));
        deliveries.add(delivery(SESSIONS, ExtractKind.FULL, FULL_EXTRACT.plusSeconds(72), 1,
            sessions(List.of(sessionA, sessionB, sessionC, sessionE), beforeFull)));
        deliveries.add(delivery(SESSION_OFFICERS, ExtractKind.FULL, FULL_EXTRACT.plusSeconds(125), 1,
            seats(List.of(sessionA, sessionB, sessionC, sessionE), beforeFull)));
        deliveries.add(delivery(HEARINGS, ExtractKind.FULL, FULL_EXTRACT, 1,
            hearings(List.of(hearingA, hearingB, hearingC, readingTime, hearingE, unassigned, hearingG), beforeFull)));
        LocalDateTime sameDayIncr = LocalDateTime.of(2040, 1, 30, 11, 0);
        List<Map<String, String>> revised = hearings(List.of(hearingB), sameDayIncr.minusHours(2));
        revised.forEach(row -> row.put(SyntheticRows.UNSELECTED_TEXT, "Synthetic value revised in same-day increment"));
        deliveries.add(delivery(HEARINGS, ExtractKind.INCR, sameDayIncr, 1, revised));

        // Phase 2: a new session with its officer and hearing; the user Incr is a valid zero-row file.
        LocalDateTime dayTwoIncr = LocalDateTime.of(2040, 1, 31, 11, 0);
        deliveries.add(delivery(SESSIONS, ExtractKind.INCR, dayTwoIncr.plusSeconds(194), 2,
            sessions(List.of(sessionD), dayTwoIncr.minusHours(3))));
        deliveries.add(delivery(SESSION_OFFICERS, ExtractKind.INCR, dayTwoIncr.plusSeconds(242), 2,
            seats(List.of(sessionD), dayTwoIncr.minusHours(3))));
        deliveries.add(delivery(HEARINGS, ExtractKind.INCR, dayTwoIncr, 2,
            hearings(List.of(hearingH), dayTwoIncr.minusHours(3))));
        deliveries.add(delivery(USER, ExtractKind.INCR, dayTwoIncr.plusSeconds(21), 2, List.of()));

        // Phase 3: officer_d's personal-code mapping arrives after the assignment, under the 2040-02 prefix.
        LocalDateTime monthRollover = LocalDateTime.of(2040, 2, 1, 2, 30);
        deliveries.add(delivery(USER, ExtractKind.INCR, monthRollover, 3,
            users(List.of(officerD), monthRollover.minusHours(4))));

        FixtureManifest.Expected expected = expected(
            List.of(hearingA, hearingB, hearingC, readingTime, hearingE, unassigned, hearingG, hearingH),
            List.of(sessionA, sessionB, sessionC, sessionD, sessionE),
            List.of(officerA, officerB, officerC, officerD, placeholder));

        return new Scenario(ID, seed, "observed-shape", BUSINESS_CLOCK, rows.symbols(), List.copyOf(deliveries),
            expected, rows.sentinels());
    }

    private Delivery delivery(String container, ExtractKind kind, LocalDateTime timestamp, int phase,
                              List<Map<String, String>> rows) {
        return new Delivery(inventory.observed(container), kind, timestamp.toLocalDate(), timestamp, phase,
            UploadAction.CREATE, rows);
    }

    private List<Map<String, String>> users(List<Officer> officers, LocalDateTime modified) {
        return officers.stream().map(officer -> rows.user(officer, modified)).toList();
    }

    private List<Map<String, String>> sessions(List<SessionDef> sessions, LocalDateTime modified) {
        return sessions.stream().map(session -> rows.session(session, modified)).toList();
    }

    private List<Map<String, String>> seats(List<SessionDef> sessions, LocalDateTime modified) {
        return sessions.stream().flatMap(session -> rows.seats(session, modified).stream()).toList();
    }

    private List<Map<String, String>> hearings(List<HearingDef> hearings, LocalDateTime modified) {
        List<Map<String, String>> result = new ArrayList<>();
        hearings.forEach(hearing -> result.addAll(rows.hearingRows(hearing, modified)));
        return result;
    }

    /**
     * Derived from the authored world definitions, not from generated rows. Nothing in the baseline changes state,
     * so the latest lifecycle of every hearing and session is its initial one.
     */
    private FixtureManifest.Expected expected(List<HearingDef> hearings, List<SessionDef> sessions,
                                              List<Officer> officers) {
        ExpectedBuilder expected = new ExpectedBuilder();
        for (HearingDef hearing : hearings) {
            String hearingId = rows.id(hearing.symbol());
            SessionDef session = hearing.session();
            String sessionId = session == null ? null : rows.id(session.symbol());
            String court = session == null ? null : rows.id(session.room().venue().symbol());
            String room = session == null ? null : rows.id(session.room().symbol());
            Map<String, String> lifecycle = session == null
                ? SyntheticRows.unlistedHearing() : SyntheticRows.listedHearing();
            if (hearing.cases().isEmpty()) {
                expected.hearing(hearingId, null, sessionId, court, room)
                    .latestHearing(hearingId, null, lifecycle);
            }
            for (CaseRef caseRef : hearing.cases()) {
                expected.hearing(hearingId, caseRef.reference(), sessionId, court, room)
                    .latestHearing(hearingId, rows.id(caseRef.symbol()), lifecycle);
            }
        }
        for (SessionDef session : sessions) {
            String sessionId = rows.id(session.symbol());
            expected.session(sessionId, rows.id(session.room().venue().symbol()), rows.id(session.room().symbol()))
                .latestSession(sessionId, SyntheticRows.releasedSession());
            session.seats().forEach(seat -> expected.sessionOfficer(sessionId, rows.id(seat.officer().symbol())));
        }
        officers.forEach(officer -> expected.user(rows.id(officer.symbol()), rows.personalCode(officer)));
        return expected.build();
    }
}
