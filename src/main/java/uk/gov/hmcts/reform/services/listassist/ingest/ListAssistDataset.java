package uk.gov.hmcts.reform.services.listassist.ingest;

import uk.gov.hmcts.reform.services.listassist.ListAssistContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The selected source columns of each core container and where they are stored. Anything not listed here (names,
 * contact details, notes) is never read from the file.
 */
public enum ListAssistDataset {

    HEARINGS(ListAssistContainer.HEARINGS, "vhmcts_Hearings", "hearing_row", "Last_Modified_Date",
        List.of("ID_Hearing"), Map.of("Hearing_Date", "hearing_date"),
        "ID_Hearing", "id_hearing",
        "ID_Case", "id_case",
        "Case_No", "case_no",
        "ID_Session", "id_session",
        "ID_Booking", "id_booking",
        "ID_JO_1", "id_jo_1",
        "ID_JO_2", "id_jo_2",
        "ID_JO_3", "id_jo_3",
        "Hearing_Date", "hearing_date_raw",
        "Hearing_DateTime", "hearing_datetime",
        "Hearing_Start_Time_24h", "start_time_24h",
        "Hearing_Duration", "duration",
        "Hearing_Type", "hearing_type",
        "Hearing_Channel", "channel",
        "CD_Locality", "cd_locality",
        "Locality", "locality",
        "CD_Location", "cd_location",
        "Location", "location",
        "CD_Jurisdiction", "cd_jurisdiction",
        "CD_Listing_Status", "cd_listing_status",
        "Listing_Status", "listing_status",
        "Listing_Cancelled_Flag", "listing_cancelled_flag",
        "Listing_Cancelled_Date", "listing_cancelled_date",
        "INACTIVE_DATE", "inactive_date",
        "Heard_Flag", "heard_flag",
        "Last_Modified_Date", "last_modified",
        "extraction_date", "extraction_date"),

    SESSIONS(ListAssistContainer.SESSIONS, "vhmcts_Sessions", "session_row", "Last_Modified_date",
        List.of("ID_Session"), Map.of("Session_Date", "session_date"),
        "ID_Session", "id_session",
        "Session_Date", "session_date_raw",
        "start_time", "start_time",
        "end_time", "end_time",
        "CD_Court", "cd_court",
        "Court", "court",
        "CD_Room", "cd_room",
        "Room", "room",
        "CD_Jurisdiction", "cd_jurisdiction",
        "CD_Session_Status", "cd_session_status",
        "Session_Status", "session_status",
        "cancelled_date", "cancelled_date",
        "INACTIVE_DATE", "inactive_date",
        "Last_Modified_date", "last_modified",
        "extraction_date", "extraction_date"),

    SESSION_OFFICERS(ListAssistContainer.SESSION_OFFICERS, "vhmcts_Sessions_JOfficer", "session_officer_row",
        "Last_Modified_Date", List.of("ID_Session", "ID_JO"), Map.of(),
        "ID_Session", "id_session",
        "ID_JO", "id_jo",
        "ID_Booking", "id_booking",
        "JO_Presiding", "jo_presiding",
        "inactive_date", "inactive_date",
        "Last_Modified_Date", "last_modified",
        "extraction_date", "extraction_date"),

    USERS(ListAssistContainer.USERS, "vhmcts_user", "user_row", "Last_Modified_Date",
        List.of("ID_User"), Map.of(),
        "ID_User", "id_user",
        "External_User_ID", "personal_code",
        "Active_From_Date", "active_from",
        "Active_to_Date", "active_to",
        "Last_Modified_Date", "last_modified",
        "extraction_date", "extraction_date");

    private final ListAssistContainer container;
    private final String viewToken;
    private final String table;
    private final String lastModifiedColumn;
    private final List<String> identityColumns;
    private final Map<String, String> parsedDates;
    private final List<String> sourceColumns;
    private final List<String> tableColumns;

    ListAssistDataset(ListAssistContainer container, String viewToken, String table, String lastModifiedColumn,
                      List<String> identityColumns, Map<String, String> parsedDates, String... sourceToTable) {
        this.container = container;
        this.viewToken = viewToken;
        this.table = table;
        this.lastModifiedColumn = lastModifiedColumn;
        this.identityColumns = identityColumns;
        this.parsedDates = parsedDates;
        List<String> sources = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < sourceToTable.length; i += 2) {
            sources.add(sourceToTable[i]);
            targets.add(sourceToTable[i + 1]);
        }
        this.sourceColumns = List.copyOf(sources);
        this.tableColumns = List.copyOf(targets);
    }

    public ListAssistContainer container() {
        return container;
    }

    /**
     * Case-sensitive token in the extract filename, e.g. {@code Full_vhmcts_Hearings}.
     */
    public String viewToken() {
        return viewToken;
    }

    public String table() {
        return table;
    }

    public String lastModifiedColumn() {
        return lastModifiedColumn;
    }

    /**
     * Source columns that must be present for a row to have an identity.
     */
    public List<String> identityColumns() {
        return identityColumns;
    }

    /**
     * Source date column to the parsed {@code date} column stored beside its raw value.
     */
    public Map<String, String> parsedDates() {
        return parsedDates;
    }

    /**
     * Selected source columns, in the same order as {@link #tableColumns()}.
     */
    public List<String> sourceColumns() {
        return sourceColumns;
    }

    public List<String> tableColumns() {
        return tableColumns;
    }
}
