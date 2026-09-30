-- ListAssist ingestion: a file ledger, a bootstrap record per container and four tables of selected source
-- observations. Current state is derived by views; nothing is deleted because it is absent from a later file.

create schema listassist;

create table listassist.source_file (
  id                 bigint generated always as identity primary key,
  container          text not null,
  blob_name          text not null,
  etag               text not null,
  extract_kind       text not null check (extract_kind in ('Full', 'Incr')),
  file_timestamp     text not null,
  status             text not null check (status in ('ingested', 'failed', 'superseded', 'skipped_pre_bootstrap')),
  error_code         text,
  attempts           integer not null default 0,
  sha256             text,
  size_bytes         bigint,
  row_count          bigint,
  unusable_row_count bigint,
  first_seen_at      timestamptz not null default now(),
  last_attempt_at    timestamptz,
  ingested_at        timestamptz,
  unique (container, blob_name, etag)
);

create table listassist.container_bootstrap (
  container       text primary key,
  source_file_id  bigint not null references listassist.source_file (id),
  bootstrapped_at timestamptz not null default now()
);

-- Source values are kept as raw text. last_modified keeps all seven fractional digits and orders byte-wise.
-- row_problem marks rows kept as evidence but excluded from current state.

create table listassist.hearing_row (
  source_file_id         bigint not null references listassist.source_file (id),
  row_no                 integer not null,
  last_modified          text collate "C",
  row_problem            text check (row_problem in ('missing_identity', 'invalid_last_modified')),
  extraction_date        text,
  id_hearing             text,
  id_case                text,
  case_no                text,
  id_session             text,
  id_booking             text,
  id_jo_1                text,
  id_jo_2                text,
  id_jo_3                text,
  hearing_date_raw       text,
  hearing_date           date,
  hearing_datetime       text,
  start_time_24h         text,
  duration               text,
  hearing_type           text,
  channel                text,
  cd_locality            text,
  locality               text,
  cd_location            text,
  location               text,
  cd_jurisdiction        text,
  cd_listing_status      text,
  listing_status         text,
  listing_cancelled_flag text,
  listing_cancelled_date text,
  inactive_date          text,
  heard_flag             text,
  primary key (source_file_id, row_no)
);

create table listassist.session_row (
  source_file_id    bigint not null references listassist.source_file (id),
  row_no            integer not null,
  last_modified     text collate "C",
  row_problem       text check (row_problem in ('missing_identity', 'invalid_last_modified')),
  extraction_date   text,
  id_session        text,
  session_date_raw  text,
  session_date      date,
  start_time        text,
  end_time          text,
  cd_court          text,
  court             text,
  cd_room           text,
  room              text,
  cd_jurisdiction   text,
  cd_session_status text,
  session_status    text,
  cancelled_date    text,
  inactive_date     text,
  primary key (source_file_id, row_no)
);

create table listassist.session_officer_row (
  source_file_id  bigint not null references listassist.source_file (id),
  row_no          integer not null,
  last_modified   text collate "C",
  row_problem     text check (row_problem in ('missing_identity', 'invalid_last_modified')),
  extraction_date text,
  id_session      text,
  id_jo           text,
  id_booking      text,
  jo_presiding    text,
  inactive_date   text,
  primary key (source_file_id, row_no)
);

create table listassist.user_row (
  source_file_id  bigint not null references listassist.source_file (id),
  row_no          integer not null,
  last_modified   text collate "C",
  row_problem     text check (row_problem in ('missing_identity', 'invalid_last_modified')),
  extraction_date text,
  id_user         text,
  personal_code   text,
  active_from     text,
  active_to       text,
  primary key (source_file_id, row_no)
);

create index hearing_row_identity on listassist.hearing_row (id_hearing, id_case, id_session, last_modified desc);
create index hearing_row_session on listassist.hearing_row (id_session);
create index hearing_row_day on listassist.hearing_row (hearing_date, id_hearing);
create index hearing_row_problem on listassist.hearing_row (row_problem) where row_problem is not null;
create index hearing_row_jo_1 on listassist.hearing_row (id_jo_1) where id_jo_1 is not null;
create index hearing_row_jo_2 on listassist.hearing_row (id_jo_2) where id_jo_2 is not null;
create index hearing_row_jo_3 on listassist.hearing_row (id_jo_3) where id_jo_3 is not null;
create index session_row_identity on listassist.session_row (id_session, last_modified desc);
create index session_officer_row_identity on listassist.session_officer_row (id_session, id_jo, last_modified desc);
create index session_officer_row_jo on listassist.session_officer_row (id_jo);
create index user_row_identity on listassist.user_row (id_user, last_modified desc);
create index user_row_personal_code on listassist.user_row (personal_code);

-- Newest views: the distinct payloads among usable rows at the greatest last_modified of each identity, with how many
-- there are. Identical payloads collapse (replays, duplicate files); payloads > 1 is a conflict at the newest version.
-- Partitioning treats null key parts as equal, so associations with a null case or session group correctly. Payloads
-- exclude provenance, extraction_date and row_problem.

create view listassist.hearing_newest as
select p.*, count(*) over (partition by p.id_hearing, p.id_case, p.id_session) as payloads
from (
  select distinct id_hearing, id_case, id_session, last_modified, case_no, id_booking, id_jo_1, id_jo_2, id_jo_3,
         hearing_date_raw, hearing_date, hearing_datetime, start_time_24h, duration, hearing_type, channel,
         cd_locality, locality, cd_location, location, cd_jurisdiction, cd_listing_status, listing_status,
         listing_cancelled_flag, listing_cancelled_date, inactive_date, heard_flag
  from (
    select h.*, max(h.last_modified) over (partition by h.id_hearing, h.id_case, h.id_session) as newest
    from listassist.hearing_row h
    where h.row_problem is null
  ) v
  where v.last_modified = v.newest
) p;

create view listassist.session_newest as
select p.*, count(*) over (partition by p.id_session) as payloads
from (
  select distinct id_session, last_modified, session_date_raw, session_date, start_time, end_time, cd_court, court,
         cd_room, room, cd_jurisdiction, cd_session_status, session_status, cancelled_date, inactive_date
  from (
    select s.*, max(s.last_modified) over (partition by s.id_session) as newest
    from listassist.session_row s
    where s.row_problem is null
  ) v
  where v.last_modified = v.newest
) p;

create view listassist.session_officer_newest as
select p.*, count(*) over (partition by p.id_session, p.id_jo) as payloads
from (
  select distinct id_session, id_jo, last_modified, id_booking, jo_presiding, inactive_date
  from (
    select o.*, max(o.last_modified) over (partition by o.id_session, o.id_jo) as newest
    from listassist.session_officer_row o
    where o.row_problem is null
  ) v
  where v.last_modified = v.newest
) p;

create view listassist.user_newest as
select p.*, count(*) over (partition by p.id_user) as payloads
from (
  select distinct id_user, last_modified, personal_code, active_from, active_to
  from (
    select u.*, max(u.last_modified) over (partition by u.id_user) as newest
    from listassist.user_row u
    where u.row_problem is null
  ) v
  where v.last_modified = v.newest
) p;

-- Current views: an identity whose newest payloads disagree has no current row. Unorderable observations are
-- reported by the candidate query and unusable_observation, not here.

create view listassist.current_hearing_association as
select * from listassist.hearing_newest where payloads = 1;

create view listassist.current_session as
select * from listassist.session_newest where payloads = 1;

create view listassist.current_session_officer as
select * from listassist.session_officer_newest where payloads = 1;

-- A blank inactive date counts as unset, so an extract that writes empty text instead of null keeps its officers.
create view listassist.session_officer_candidate as
select * from listassist.current_session_officer where nullif(btrim(inactive_date, E' \t\r\n'), '') is null;

create view listassist.current_user_account as
select * from listassist.user_newest where payloads = 1;

-- Every identity and version whose usable rows disagree, at any version, so older conflicts stay visible.

create view listassist.observation_conflict as
select 'hearing' as dataset, jsonb_build_array(id_hearing, id_case, id_session)::text as identity, last_modified,
       count(*) as payloads
from (
  select distinct id_hearing, id_case, id_session, last_modified, case_no, id_booking, id_jo_1, id_jo_2, id_jo_3,
         hearing_date_raw, hearing_datetime, start_time_24h, duration, hearing_type, channel, cd_locality, locality,
         cd_location, location, cd_jurisdiction, cd_listing_status, listing_status, listing_cancelled_flag,
         listing_cancelled_date, inactive_date, heard_flag
  from listassist.hearing_row where row_problem is null
) d
group by id_hearing, id_case, id_session, last_modified
having count(*) > 1
union all
select 'session', id_session, last_modified, count(*)
from (
  select distinct id_session, last_modified, session_date_raw, start_time, end_time, cd_court, court, cd_room, room,
         cd_jurisdiction, cd_session_status, session_status, cancelled_date, inactive_date
  from listassist.session_row where row_problem is null
) d
group by id_session, last_modified
having count(*) > 1
union all
select 'session_officer', jsonb_build_array(id_session, id_jo)::text, last_modified, count(*)
from (
  select distinct id_session, id_jo, last_modified, id_booking, jo_presiding, inactive_date
  from listassist.session_officer_row where row_problem is null
) d
group by id_session, id_jo, last_modified
having count(*) > 1
union all
select 'user', id_user, last_modified, count(*)
from (
  select distinct id_user, last_modified, personal_code, active_from, active_to
  from listassist.user_row where row_problem is null
) d
group by id_user, last_modified
having count(*) > 1;

create view listassist.unusable_observation as
select 'hearing' as dataset, source_file_id, row_no, row_problem,
       jsonb_build_array(id_hearing, id_case, id_session)::text as identity
from listassist.hearing_row where row_problem is not null
union all
select 'session', source_file_id, row_no, row_problem, id_session
from listassist.session_row where row_problem is not null
union all
select 'session_officer', source_file_id, row_no, row_problem, jsonb_build_array(id_session, id_jo)::text
from listassist.session_officer_row where row_problem is not null
union all
select 'user', source_file_id, row_no, row_problem, id_user
from listassist.user_row where row_problem is not null;
