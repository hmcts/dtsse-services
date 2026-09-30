-- Production read access for the "Database - dtsse read access" JIT package. The postgres module creates the role
-- and grants public, but its default privileges only cover tables its own identity creates, not ones Flyway creates
-- as pgadmin. The role only exists in production so this is a no-op everywhere else.

do $$
begin
  if exists (select from pg_roles where rolname = 'DTS JIT Access dtsse DB Reader SC') then
    grant usage on schema listassist to "DTS JIT Access dtsse DB Reader SC";
    grant select on all tables in schema listassist to "DTS JIT Access dtsse DB Reader SC";
    alter default privileges in schema listassist grant select on tables to "DTS JIT Access dtsse DB Reader SC";
  end if;
end
$$;
