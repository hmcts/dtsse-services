package uk.gov.hmcts.reform.services.listassist.fixtures;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Builds invented source rows for a small ListAssist world. Returned rows are mutable so a scenario can apply a
 * lifecycle change before handing them to a {@link Delivery}, which rejects any column not in the inventory.
 */
final class SyntheticRows {

    static final List<String> HEARING_LIFECYCLE = List.of(
        "Listing_Cancelled_Flag", "Listing_Cancelled_Date", "CD_Listing_Status", "Listing_Status", "INACTIVE_DATE",
        "Heard_Flag");
    static final List<String> SESSION_LIFECYCLE = List.of(
        "CD_Session_Status", "Session_Status", "INACTIVE_DATE", "cancelled_date");

    // Source flag encodings: Heard_Flag is No/Yes and JO_Presiding is False/True.
    static final String HEARD = "Yes";
    static final String NOT_HEARD = "No";
    static final String TRUE = "True";
    static final String FALSE = "False";

    // Generic columns the application never reads.
    static final String UNSELECTED_SENTINEL = "Unselected_1";
    static final String UNSELECTED_NULL = "Unselected_2";
    static final String UNSELECTED_TEXT = "Unselected_3";

    private static final DateTimeFormatter TIME_24H = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SyntheticIds ids;
    private final List<String> sentinels = new ArrayList<>();

    SyntheticRows(SyntheticIds ids) {
        this.ids = ids;
    }

    String id(String symbol) {
        return ids.id(symbol);
    }

    String personalCode(Officer officer) {
        return officer.hasPersonalCode()
            ? ids.personalCode(officer.symbol() + "_personal_code", officer.leadingZeroCode()) : null;
    }

    String replacementPersonalCode(Officer officer) {
        return ids.personalCode(officer.symbol() + "_personal_code_replacement", false);
    }

    Map<String, String> symbols() {
        return ids.symbols();
    }

    List<String> sentinels() {
        return List.copyOf(new TreeSet<>(sentinels));
    }

    Map<String, String> user(Officer officer, LocalDateTime modified) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("ID_User", id(officer.symbol()));
        row.put("External_User_ID", personalCode(officer));
        row.put("Active_From_Date", midnight(LocalDate.of(2039, 4, 1)));
        row.put("Active_to_Date", null);
        unselected(row, "USER-" + officer.label().toUpperCase(Locale.ROOT));
        row.put("Last_Modified_Date", timestamp(modified));
        row.put("extraction_date", modified.toLocalDate().plusDays(1).toString());
        return row;
    }

    Map<String, String> session(SessionDef session, LocalDateTime modified) {
        Room room = session.room();
        Map<String, String> row = new LinkedHashMap<>();
        row.put("ID_Session", id(session.symbol()));
        row.put("Session_Date", midnight(session.date()));
        row.put("start_time", exact(session.date().atTime(session.start())));
        row.put("end_time", exact(session.date().atTime(session.end())));
        row.put("CD_Court", id(room.venue().symbol()));
        row.put("Court", room.venue().name());
        row.put("CD_Room", id(room.symbol()));
        row.put("Room", room.name());
        row.put("Hearing_Channel", "Synthetic In Person");
        row.put("CD_Jurisdiction", "SYN-CIV");
        row.putAll(releasedSession());
        unselected(row, "SESSION-" + session.symbol().toUpperCase(Locale.ROOT));
        row.put("Last_Modified_date", timestamp(modified));
        row.put("extraction_date", modified.toLocalDate().plusDays(1).toString());
        return row;
    }

    Map<String, String> seat(SessionDef session, Seat seat, LocalDateTime modified) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("ID_JO", id(seat.officer().symbol()));
        row.put("ID_Session", id(session.symbol()));
        row.put("ID_Booking", seat.bookingSymbol() == null ? null : id(seat.bookingSymbol()));
        row.put("JO_Presiding", seat.presiding() ? TRUE : FALSE);
        row.put("inactive_date", null);
        unselected(row, "JO-" + seat.officer().label().toUpperCase(Locale.ROOT));
        row.put("Last_Modified_Date", timestamp(modified));
        row.put("extraction_date", modified.toLocalDate().plusDays(1).toString());
        return row;
    }

    List<Map<String, String>> seats(SessionDef session, LocalDateTime modified) {
        return session.seats().stream().map(seat -> seat(session, seat, modified)).toList();
    }

    /**
     * One hearing-case association row; {@code caseRef} is null for a non-case entry such as reading time.
     * Hearing {@code Location}/{@code CD_Location} carry the room and {@code Locality}/{@code CD_Locality} the court.
     */
    Map<String, String> hearing(HearingDef hearing, CaseRef caseRef, LocalDateTime modified) {
        SessionDef session = hearing.session();
        Map<String, String> row = new LinkedHashMap<>();
        row.put("ID_Hearing", id(hearing.symbol()));
        row.put("ID_Case", caseRef == null ? null : id(caseRef.symbol()));
        row.put("Case_No", caseRef == null ? null : caseRef.reference());
        row.put("Hearing_Type", hearing.type());
        row.put("Hearing_Date", midnight(hearing.date()));
        row.put("Hearing_Duration", String.valueOf(hearing.durationMinutes()));
        row.put("Hearing_Channel", hearing.channel());
        row.putAll(session == null ? unlistedHearing() : listedHearing());
        row.put("CD_Jurisdiction", "SYN-CIV");
        unselected(row, "HEARING-" + hearing.symbol().toUpperCase(Locale.ROOT));
        row.put("Last_Modified_Date", timestamp(modified));
        row.put("extraction_date", modified.toLocalDate().plusDays(1).toString());
        if (session != null) {
            Room room = session.room();
            row.put("ID_Session", id(session.symbol()));
            row.put("ID_Booking", id(hearing.symbol() + "_booking"));
            row.put("Hearing_DateTime", exact(hearing.date().atTime(hearing.start())));
            row.put("Hearing_Start_Time_24h", hearing.start().format(TIME_24H));
            row.put("Session_Date", midnight(session.date()));
            row.put("CD_Locality", id(room.venue().symbol()));
            row.put("Locality", room.venue().name());
            row.put("CD_Location", id(room.symbol()));
            row.put("Location", room.name());
            // Unambiguous by default: hearing-level officer slots match the session seats.
            List<Seat> seats = session.seats();
            for (int slot = 0; slot < Math.min(3, seats.size()); slot++) {
                row.put("ID_JO_" + (slot + 1), id(seats.get(slot).officer().symbol()));
            }
        }
        return row;
    }

    List<Map<String, String>> hearingRows(HearingDef hearing, LocalDateTime modified) {
        if (hearing.cases().isEmpty()) {
            List<Map<String, String>> single = new ArrayList<>();
            single.add(hearing(hearing, null, modified));
            return single;
        }
        return hearing.cases().stream().map(caseRef -> hearing(hearing, caseRef, modified)).toList();
    }

    static Map<String, String> listedHearing() {
        return hearingLifecycle("0", null, "SYN-LST", "Synthetic Listed");
    }

    static Map<String, String> unlistedHearing() {
        return hearingLifecycle("0", null, "SYN-UNL", "Synthetic Unlisted");
    }

    static Map<String, String> heardHearing() {
        Map<String, String> lifecycle = listedHearing();
        lifecycle.put("Heard_Flag", HEARD);
        return lifecycle;
    }

    static Map<String, String> cancelledHearing(String cancelledDate) {
        return hearingLifecycle("1", cancelledDate, "SYN-CNL", "Synthetic Cancelled");
    }

    static Map<String, String> releasedSession() {
        return sessionLifecycle("SYN-REL", "Synthetic Released", null);
    }

    static Map<String, String> cancelledSession(String cancelledDate) {
        return sessionLifecycle("SYN-CNL", "Synthetic Cancelled", cancelledDate);
    }

    private static Map<String, String> hearingLifecycle(String flag, String cancelledDate, String code, String label) {
        Map<String, String> lifecycle = new LinkedHashMap<>();
        lifecycle.put("Listing_Cancelled_Flag", flag);
        lifecycle.put("Listing_Cancelled_Date", cancelledDate);
        lifecycle.put("CD_Listing_Status", code);
        lifecycle.put("Listing_Status", label);
        lifecycle.put("INACTIVE_DATE", null);
        lifecycle.put("Heard_Flag", NOT_HEARD);
        return lifecycle;
    }

    private static Map<String, String> sessionLifecycle(String code, String label, String cancelledDate) {
        Map<String, String> lifecycle = new LinkedHashMap<>();
        lifecycle.put("CD_Session_Status", code);
        lifecycle.put("Session_Status", label);
        lifecycle.put("INACTIVE_DATE", null);
        lifecycle.put("cancelled_date", cancelledDate);
        return lifecycle;
    }

    /**
     * A privacy sentinel that must never reach the database, an all-null column and free text a scenario may revise.
     */
    private void unselected(Map<String, String> row, String label) {
        row.put(UNSELECTED_SENTINEL, sentinel("SENTINEL-S01-" + label));
        row.put(UNSELECTED_NULL, null);
        row.put(UNSELECTED_TEXT, "Synthetic unselected value");
    }

    private String sentinel(String value) {
        sentinels.add(value);
        return value;
    }

    String timestamp(LocalDateTime value) {
        return value.format(DATE_TIME) + "." + String.format(Locale.ROOT, "%07d", ids.fraction());
    }

    static String exact(LocalDateTime value) {
        return value.format(DATE_TIME) + ".0000000";
    }

    static String midnight(LocalDate date) {
        return exact(date.atStartOfDay());
    }

    record Venue(String symbol, String name) {
    }

    record Room(String symbol, Venue venue, String name) {
    }

    record Officer(String symbol, String label, boolean leadingZeroCode, boolean hasPersonalCode) {
    }

    record Seat(Officer officer, boolean presiding, String bookingSymbol) {
    }

    record SessionDef(String symbol, Room room, LocalDate date, LocalTime start, LocalTime end, List<Seat> seats) {
    }

    record CaseRef(String symbol, String reference) {
    }

    record HearingDef(String symbol, SessionDef session, LocalDate date, LocalTime start, int durationMinutes,
                      String type, List<CaseRef> cases, String channel) {
    }
}
