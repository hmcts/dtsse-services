package uk.gov.hmcts.reform.services.listassist.query;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.services.listassist.ListAssistContainer;
import uk.gov.hmcts.reform.services.listassist.query.CandidateHearings.Candidate;
import uk.gov.hmcts.reform.services.listassist.query.CandidateHearings.ContainerStatus;
import uk.gov.hmcts.reform.services.listassist.query.CandidateHearings.Diagnostic;
import uk.gov.hmcts.reform.services.listassist.query.CandidateHearings.SessionOfficer;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Candidate hearings for a judge's personal code on a source hearing date.
 *
 * <p>Personal code, hearing officer slots and hearing date can change between versions, so they are only used to
 * discover identity keys. Current state is resolved for those keys first and the condition is then checked again,
 * so an old code or a removed officer never retrieves a hearing. All reads share one snapshot.
 */
@Repository
public class ListAssistCandidateRepository {

    private static final int SAMPLE_LIMIT = 10;
    private static final String SLOT_MATCH =
        "(id_jo_1 = any(:users) or id_jo_2 = any(:users) or id_jo_3 = any(:users))";

    private final NamedParameterJdbcTemplate jdbc;

    public ListAssistCandidateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CandidateHearings findCandidates(String personalCode, LocalDate date) {
        Diagnostics diagnostics = new Diagnostics();
        final List<ContainerStatus> containers = containers(diagnostics);

        // 1. Users: discover by any historical code, keep those whose current code still matches.
        MapSqlParameterSource code = new MapSqlParameterSource("code", personalCode);
        String discoveredUsers = "array(select id_user from listassist.user_row where personal_code = :code)";
        String[] users = strings("select id_user from listassist.current_user_account where id_user = any("
            + discoveredUsers + ") and personal_code = :code order by 1", code);
        final Set<String> userSet = new HashSet<>(Arrays.asList(users));
        if (users.length == 0) {
            diagnostics.add("PERSONAL_CODE_UNMATCHED", null);
        }
        diagnostics.addAll("USER_CONFLICT", strings("select id_user from listassist.user_newest where payloads > 1"
            + " and id_user = any(" + discoveredUsers + ") group by id_user having bool_or(personal_code = :code)",
            code));
        diagnostics.addAll("UNORDERABLE_OBSERVATIONS", strings("select distinct 'user:' || id_user"
            + " from listassist.user_row where row_problem = 'invalid_last_modified'"
            + " and id_user = any(" + discoveredUsers + ")", code));

        // 2. Sessions where a matched user is a current, active officer (ID_JO is part of that identity).
        MapSqlParameterSource params = new MapSqlParameterSource("users", users).addValue("date", date);
        String[] sessions = strings("select distinct id_session from listassist.session_officer_candidate"
            + " where id_jo = any(:users)", params);
        params.addValue("sessions", sessions);
        diagnostics.addAll("OFFICER_CONFLICT", strings("select distinct format('%s|%s', id_session, id_jo)"
            + " from listassist.session_officer_newest where payloads > 1 and id_jo = any(:users)", params));

        // 3. Current associations on those sessions, and those whose current slots still hold a matched user.
        Set<AssociationKey> slotKeys = new HashSet<>(jdbc.query("select distinct id_hearing, id_case, id_session"
            + " from listassist.hearing_row where " + SLOT_MATCH, params, ListAssistCandidateRepository::key));
        params.addValue("slotHearings", slotKeys.stream().map(AssociationKey::idHearing).distinct()
            .toArray(String[]::new));
        // One read of both routes; the date is checked after current state is resolved, and a null parsed date is
        // kept for INVALID_HEARING_DATE. Which route matched is decided per row from its current values.
        Set<String> sessionScope = new HashSet<>(Arrays.asList(sessions));
        Map<AssociationKey, Match> matches = new LinkedHashMap<>();
        jdbc.query("select * from listassist.current_hearing_association"
            + " where (id_session = any(:sessions) or id_hearing = any(:slotHearings))"
            + " and (hearing_date = :date or hearing_date is null)", params, (rs, n) -> {
                AssociationRow row = association(rs, n);
                boolean session = sessionScope.contains(row.key().idSession());
                boolean slot = row.slots().stream().anyMatch(userSet::contains);
                if (session || slot) {
                    if (row.hearingDate() == null && row.hearingDateRaw() != null) {
                        diagnostics.add("INVALID_HEARING_DATE", row.key().display());
                    } else if (date.equals(row.hearingDate())) {
                        matches.put(row.key(), new Match(row, session, slot));
                    }
                }
                return null;
            });
        List<AssociationKey> conflicted = jdbc.query("select id_hearing, id_case, id_session"
            + " from listassist.hearing_newest where payloads > 1"
            + " and (id_session = any(:sessions) or id_hearing = any(:slotHearings))"
            + " group by id_hearing, id_case, id_session"
            + " having bool_or(hearing_date = :date and (id_session = any(:sessions) or " + SLOT_MATCH + "))",
            params, ListAssistCandidateRepository::key);
        conflicted.forEach(key -> diagnostics.add("HEARING_CONFLICT", key.display()));

        // 4. Session state and panel for the matched associations.
        String[] candidateSessions = matches.keySet().stream().map(AssociationKey::idSession)
            .filter(Objects::nonNull).distinct().toArray(String[]::new);
        params.addValue("candidateSessions", candidateSessions);
        Map<String, SessionRow> knownSessions = new HashMap<>();
        jdbc.query("select id_session, cancelled_date, inactive_date from listassist.current_session"
                + " where id_session = any(:candidateSessions)", params,
            (rs, n) -> knownSessions.put(rs.getString(1), new SessionRow(rs.getString(2), rs.getString(3))));
        final Set<String> conflictedSessions = Set.of(strings("select distinct id_session"
            + " from listassist.session_newest where payloads > 1 and id_session = any(:candidateSessions)", params));
        Map<String, List<SessionOfficer>> panels = new HashMap<>();
        jdbc.query("select id_session, id_jo, jo_presiding, id_booking from listassist.session_officer_candidate"
                + " where id_session = any(:candidateSessions) order by id_jo", params,
            (rs, n) -> panels.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                .add(new SessionOfficer(rs.getString(2), rs.getString(3), rs.getString(4))));

        // Unorderable observations of every identity this answer depends on, whether or not it has a current row.
        // An association is relevant if it is a candidate or conflict here, or its malformed row is on or lacks
        // the date.
        diagnostics.addAll("UNORDERABLE_OBSERVATIONS", strings("""
            select distinct 'session_officer:' || format('%s|%s', id_session, id_jo)
              from listassist.session_officer_row
             where row_problem = 'invalid_last_modified'
               and (id_jo = any(:users) or id_session = any(:candidateSessions))
            union
            select distinct 'session:' || id_session from listassist.session_row
             where row_problem = 'invalid_last_modified' and id_session = any(:candidateSessions)
            """, params));
        Set<AssociationKey> relevant = new HashSet<>(matches.keySet());
        relevant.addAll(conflicted);
        jdbc.query("""
            select distinct id_hearing, id_case, id_session, hearing_date from listassist.hearing_row
             where row_problem = 'invalid_last_modified'
               and (id_session = any(:sessions) or id_hearing = any(:slotHearings))
            """, params, (rs, n) -> {
                AssociationKey key = key(rs, n);
                Date hearingDate = rs.getDate(4);
                boolean inScope = sessionScope.contains(key.idSession()) || slotKeys.contains(key);
                boolean onDate = hearingDate == null || date.equals(hearingDate.toLocalDate());
                if (relevant.contains(key) || inScope && onDate) {
                    diagnostics.add("UNORDERABLE_OBSERVATIONS", "hearing:" + key.display());
                }
                return null;
            });
        // A row without its identity has no key to report, so it is identified by file and row number. It is
        // relevant if it carries the personal code, a matched user, or a discovered session on (or without) the date.
        diagnostics.addAll("MISSING_IDENTITY", strings("""
            select format('user:%s#%s', f.blob_name, r.row_no)
              from listassist.user_row r join listassist.source_file f on f.id = r.source_file_id
             where r.row_problem = 'missing_identity' and r.personal_code = :code
            union
            select format('session_officer:%s#%s', f.blob_name, r.row_no)
              from listassist.session_officer_row r join listassist.source_file f on f.id = r.source_file_id
             where r.row_problem = 'missing_identity'
               and (r.id_jo = any(:users) or r.id_session = any(:candidateSessions))
            union
            select format('hearing:%s#%s', f.blob_name, r.row_no)
              from listassist.hearing_row r join listassist.source_file f on f.id = r.source_file_id
             where r.row_problem = 'missing_identity'
               and (r.id_session = any(:sessions) or r.id_jo_1 = any(:users) or r.id_jo_2 = any(:users)
                    or r.id_jo_3 = any(:users))
               and (r.hearing_date = :date or r.hearing_date is null)
            """, params.addValue("code", personalCode)));

        List<Candidate> candidates = new ArrayList<>();
        for (Match match : matches.values()) {
            AssociationRow row = match.row();
            String idSession = row.key().idSession();
            SessionRow session = idSession == null ? null : knownSessions.get(idSession);
            String sessionState = session != null ? "known"
                : idSession != null && conflictedSessions.contains(idSession) ? "conflict" : "unknown";
            if (hidden(row, session)) {
                continue;
            }
            if ("conflict".equals(sessionState)) {
                diagnostics.add("SESSION_CONFLICT", idSession);
            } else if ("unknown".equals(sessionState)) {
                diagnostics.add("SESSION_UNRESOLVED", row.key().display());
            }
            List<SessionOfficer> panel = idSession == null ? List.of() : panels.getOrDefault(idSession, List.of());
            candidates.add(candidate(row, match, userSet, sessionState, panel));
        }
        candidates.sort(Comparator.comparing(Candidate::startTime24h, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Candidate::idHearing)
            .thenComparing(Candidate::idCase, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Candidate::idSession, Comparator.nullsLast(Comparator.naturalOrder())));
        return new CandidateHearings(List.copyOf(candidates), diagnostics.list(), containers);
    }

    private List<ContainerStatus> containers(Diagnostics diagnostics) {
        List<ContainerStatus> statuses = new ArrayList<>();
        for (ListAssistContainer container : ListAssistContainer.values()) {
            ContainerStatus status = jdbc.queryForObject("""
                select exists (select 1 from listassist.container_bootstrap where container = :container),
                       count(*) filter (where status = 'failed'),
                       max(ingested_at) filter (where status = 'ingested'),
                       max(file_timestamp) filter (where status = 'ingested')
                  from listassist.source_file where container = :container
                """, new MapSqlParameterSource("container", container.key()), (rs, n) -> {
                    Timestamp last = rs.getTimestamp(3);
                    return new ContainerStatus(container.key(), rs.getBoolean(1), rs.getInt(2),
                        last == null ? null : last.toInstant(), rs.getString(4));
                });
            if (!status.bootstrapped()) {
                diagnostics.add("NOT_BOOTSTRAPPED", container.key());
            }
            if (status.failedFiles() > 0) {
                diagnostics.add("INGESTION_FAILED", container.key());
            }
            statuses.add(status);
        }
        return List.copyOf(statuses);
    }

    private String[] strings(String sql, MapSqlParameterSource params) {
        return jdbc.queryForList(sql, params, String.class).toArray(String[]::new);
    }

    /**
     * Explicit cancellation or inactivity hides an association. A blank date counts as unset, so an extract that
     * writes empty text instead of null cannot hide every hearing.
     */
    private static boolean hidden(AssociationRow row, SessionRow session) {
        return "1".equals(row.listingCancelledFlag()) || isSet(row.inactiveDate())
            || session != null && (isSet(session.cancelledDate()) || isSet(session.inactiveDate()));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static Candidate candidate(AssociationRow row, Match match, Set<String> users, String sessionState,
                                       List<SessionOfficer> panel) {
        Set<String> matchedUsers = new TreeSet<>();
        if (match.session()) {
            panel.stream().map(SessionOfficer::idJo).filter(users::contains).forEach(matchedUsers::add);
        }
        if (match.slot()) {
            row.slots().stream().filter(users::contains).forEach(matchedUsers::add);
        }
        String basis = match.session() && match.slot() ? "both" : match.session() ? "session" : "hearing_slot";
        AssociationKey key = row.key();
        return new Candidate(key.idHearing(), key.idCase(), row.caseNo(), key.idSession(), row.idBooking(),
            row.hearingDateRaw(), row.hearingDateTime(), row.startTime24h(), row.duration(), row.hearingType(),
            row.channel(), row.cdLocality(), row.locality(), row.cdLocation(), row.location(), row.cdJurisdiction(),
            row.listingStatusCode(), row.listingStatus(), row.heardFlag(), basis, List.copyOf(matchedUsers),
            List.copyOf(panel), row.slots(), sessionState);
    }

    private static AssociationKey key(ResultSet rs, int rowNum) throws SQLException {
        return new AssociationKey(rs.getString("id_hearing"), rs.getString("id_case"), rs.getString("id_session"));
    }

    private static AssociationRow association(ResultSet rs, int rowNum) throws SQLException {
        Date date = rs.getDate("hearing_date");
        return new AssociationRow(key(rs, rowNum),
            rs.getString("case_no"), rs.getString("id_booking"),
            Stream.of(rs.getString("id_jo_1"), rs.getString("id_jo_2"), rs.getString("id_jo_3"))
                .filter(Objects::nonNull).toList(),
            rs.getString("hearing_date_raw"), date == null ? null : date.toLocalDate(),
            rs.getString("hearing_datetime"), rs.getString("start_time_24h"), rs.getString("duration"),
            rs.getString("hearing_type"), rs.getString("channel"), rs.getString("cd_locality"),
            rs.getString("locality"), rs.getString("cd_location"), rs.getString("location"),
            rs.getString("cd_jurisdiction"), rs.getString("cd_listing_status"), rs.getString("listing_status"),
            rs.getString("listing_cancelled_flag"), rs.getString("inactive_date"), rs.getString("heard_flag"));
    }

    /**
     * Null-safe association identity. {@link #display()} renders it unambiguously for diagnostics, e.g.
     * {@code ["H1", null, "S1"]}, the same text as PostgreSQL's {@code jsonb_build_array(...)::text}, so a null and an
     * empty part stay distinct.
     */
    private record AssociationKey(String idHearing, String idCase, String idSession) {
        String display() {
            return Stream.of(idHearing, idCase, idSession)
                .map(part -> part == null ? "null" : '"' + part.replace("\\", "\\\\").replace("\"", "\\\"") + '"')
                .collect(Collectors.joining(", ", "[", "]"));
        }
    }

    private record AssociationRow(AssociationKey key, String caseNo, String idBooking, List<String> slots,
                                  String hearingDateRaw, LocalDate hearingDate, String hearingDateTime,
                                  String startTime24h, String duration, String hearingType, String channel,
                                  String cdLocality, String locality, String cdLocation, String location,
                                  String cdJurisdiction, String listingStatusCode, String listingStatus,
                                  String listingCancelledFlag, String inactiveDate, String heardFlag) {
    }

    private record SessionRow(String cancelledDate, String inactiveDate) {
    }

    private record Match(AssociationRow row, boolean session, boolean slot) {
    }

    /**
     * Counts distinct identifiers per code and keeps at most {@link #SAMPLE_LIMIT} samples of each.
     */
    private static final class Diagnostics {
        private final Map<String, Set<String>> ids = new TreeMap<>();

        void add(String code, String id) {
            Set<String> values = ids.computeIfAbsent(code, k -> new TreeSet<>());
            if (id != null) {
                values.add(id);
            }
        }

        void addAll(String code, String... values) {
            Arrays.stream(values).forEach(value -> add(code, value));
        }

        List<Diagnostic> list() {
            return ids.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty() || "PERSONAL_CODE_UNMATCHED".equals(entry.getKey()))
                .map(entry -> new Diagnostic(entry.getKey(), Math.max(1, entry.getValue().size()),
                    entry.getValue().stream().limit(SAMPLE_LIMIT).toList()))
                .toList();
        }
    }
}
