-- Pocket Agent: accounts' names + "Talk to us" chat. Lives in the HanuBees project (whfxrovgvulmhqkhumuz).
-- Everything is prefixed pa_ so nothing existing (e.g. public.messages) is touched.
-- Who is an admin is a row in pa_admins, added by hand (never from the app).

create table if not exists public.pa_profiles (
  user_id    uuid primary key references auth.users on delete cascade,
  name       text not null check (char_length(name) between 1 and 80),
  mobile     text check (mobile is null or char_length(mobile) <= 20),
  created_at timestamptz not null default now(),
  last_seen  timestamptz not null default now()
);

create table if not exists public.pa_admins (
  user_id uuid primary key references auth.users on delete cascade
);

-- one conversation per person: user_id is the person (customer), from_admin says who wrote it
create table if not exists public.pa_messages (
  id         bigint generated always as identity primary key,
  user_id    uuid not null references auth.users on delete cascade,
  from_admin boolean not null default false,
  body       text not null check (char_length(body) between 1 and 4000),
  created_at timestamptz not null default now(),
  read_at    timestamptz
);
create index if not exists pa_messages_user_id_id on public.pa_messages (user_id, id);
create index if not exists pa_messages_unread on public.pa_messages (from_admin) where read_at is null;

create or replace function public.pa_is_admin() returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.pa_admins where user_id = auth.uid())
$$;

-- admin's inbox: one row per person who has written, newest first
create or replace function public.pa_inbox()
returns table (user_id uuid, name text, mobile text, email text, last_body text, last_at timestamptz, last_from_admin boolean, unread int)
language sql stable security definer set search_path = '' as $$
  select m.user_id, p.name, p.mobile, u.email::text, lm.body, lm.created_at, lm.from_admin,
         (select count(*) from public.pa_messages x
           where x.user_id = m.user_id and not x.from_admin and x.read_at is null)::int
  from (select distinct pm.user_id from public.pa_messages pm) m
  left join public.pa_profiles p on p.user_id = m.user_id
  left join auth.users u on u.id = m.user_id
  cross join lateral (select y.body, y.created_at, y.from_admin from public.pa_messages y
                      where y.user_id = m.user_id order by y.id desc limit 1) lm
  where public.pa_is_admin()
  order by lm.created_at desc
  limit 300
$$;

-- Supabase grants EXECUTE to anon/authenticated by default: close it, then open only what is needed
revoke all on function public.pa_is_admin() from public, anon, authenticated;
revoke all on function public.pa_inbox() from public, anon, authenticated;
grant execute on function public.pa_is_admin() to authenticated;
grant execute on function public.pa_inbox() to authenticated;

alter table public.pa_profiles enable row level security;
alter table public.pa_admins   enable row level security;
alter table public.pa_messages enable row level security;

-- signed-out visitors get nothing at all
revoke all on public.pa_profiles, public.pa_admins, public.pa_messages from anon;
revoke all on public.pa_profiles, public.pa_admins, public.pa_messages from authenticated;
grant select, insert on public.pa_profiles to authenticated;
-- user_id is included because "save or update" (upsert) rewrites it; the policy still pins it to auth.uid()
grant update (user_id, name, mobile, last_seen) on public.pa_profiles to authenticated;
grant select on public.pa_admins to authenticated;
grant select on public.pa_messages to authenticated;
grant insert (user_id, from_admin, body) on public.pa_messages to authenticated;
grant update (read_at) on public.pa_messages to authenticated;

drop policy if exists pa_profiles_read on public.pa_profiles;
drop policy if exists pa_profiles_insert on public.pa_profiles;
drop policy if exists pa_profiles_update on public.pa_profiles;
create policy pa_profiles_read   on public.pa_profiles for select to authenticated using (user_id = auth.uid() or public.pa_is_admin());
create policy pa_profiles_insert on public.pa_profiles for insert to authenticated with check (user_id = auth.uid());
create policy pa_profiles_update on public.pa_profiles for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists pa_admins_self on public.pa_admins;
create policy pa_admins_self on public.pa_admins for select to authenticated using (user_id = auth.uid());

drop policy if exists pa_messages_read on public.pa_messages;
drop policy if exists pa_messages_write on public.pa_messages;
drop policy if exists pa_messages_mark on public.pa_messages;
create policy pa_messages_read on public.pa_messages for select to authenticated
  using (user_id = auth.uid() or public.pa_is_admin());
create policy pa_messages_write on public.pa_messages for insert to authenticated
  with check ((user_id = auth.uid() and from_admin = false) or (public.pa_is_admin() and from_admin = true));
create policy pa_messages_mark on public.pa_messages for update to authenticated
  using (user_id = auth.uid() or public.pa_is_admin())
  with check (user_id = auth.uid() or public.pa_is_admin());
