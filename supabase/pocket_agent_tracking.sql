-- Pocket Agent tracking (like fxabsolute.com): an append-only event log + one row per phone.
-- Written ONLY by the Vercel function /api/track (service role, adds country/city from the edge).
-- Read ONLY through pa_dashboard() / pa_people() / pa_feed(), which check pa_is_admin().

create table if not exists public.pa_devices (
  device_id    text primary key check (char_length(device_id) between 8 and 64),
  first_seen   timestamptz not null default now(),
  last_seen    timestamptz not null default now(),
  user_id      uuid references auth.users on delete set null,
  model        text,
  os           text,
  app_version  text,
  locale       text,
  timezone     text,
  country      text,
  region       text,
  city         text,
  used_ms      bigint not null default 0,   -- time in app, only ever moves forward
  opens        int not null default 0,
  tasks        int not null default 0,
  tasks_ok     int not null default 0,
  wall_at      timestamptz,
  signed_up_at timestamptz
);
create index if not exists pa_devices_user on public.pa_devices (user_id);

create table if not exists public.pa_events (
  id          bigint generated always as identity primary key,
  at          timestamptz not null default now(),
  device_id   text not null,
  user_id     uuid,
  name        text not null check (char_length(name) <= 40),
  props       jsonb not null default '{}'::jsonb,
  app_version text,
  country     text,
  city        text
);
create index if not exists pa_events_at on public.pa_events (at desc);
create index if not exists pa_events_name_at on public.pa_events (name, at desc);
create index if not exists pa_events_device on public.pa_events (device_id, at);

alter table public.pa_devices enable row level security;
alter table public.pa_events  enable row level security;
revoke all on public.pa_devices, public.pa_events from public, anon, authenticated;

-- one call per batch from /api/track. p = {device_id, user_id, app_version, device:{...}, geo:{...}, events:[{name, at, props}]}
create or replace function public.pa_ingest(p jsonb) returns void
language plpgsql security definer set search_path = '' as $$
declare
  d   text := left(p->>'device_id', 64);
  u   uuid := nullif(p->>'user_id', '')::uuid;
  dev jsonb := coalesce(p->'device', '{}'::jsonb);
  geo jsonb := coalesce(p->'geo', '{}'::jsonb);
  ev  jsonb;
  n_open int := 0; n_task int := 0; n_ok int := 0; walled timestamptz; signed timestamptz;
begin
  if d is null or char_length(d) < 8 then return; end if;
  for ev in select * from jsonb_array_elements(coalesce(p->'events', '[]'::jsonb)) limit 60 loop
    insert into public.pa_events (at, device_id, user_id, name, props, app_version, country, city)
    values (
      least(now(), greatest(now() - interval '3 days', coalesce(to_timestamp((ev->>'at')::double precision / 1000), now()))),
      d, u, left(ev->>'name', 40),
      case when pg_column_size(ev->'props') < 6000 then coalesce(ev->'props', '{}'::jsonb) else '{"cut":true}'::jsonb end,
      left(p->>'app_version', 20), left(geo->>'country', 4), left(geo->>'city', 80));
    if ev->>'name' = 'app_open' then n_open := n_open + 1; end if;
    if ev->>'name' = 'task_done' then
      n_task := n_task + 1;
      if ev->'props'->>'outcome' = 'ok' then n_ok := n_ok + 1; end if;
    end if;
    if ev->>'name' = 'wall_shown' then walled := coalesce(walled, now()); end if;
    if ev->>'name' = 'signup_completed' then signed := coalesce(signed, now()); end if;
  end loop;

  insert into public.pa_devices as x (device_id, user_id, model, os, app_version, locale, timezone, country, region, city,
                                      used_ms, opens, tasks, tasks_ok, wall_at, signed_up_at)
  values (d, u, left(dev->>'model', 60), left(dev->>'os', 30), left(p->>'app_version', 20), left(dev->>'locale', 20),
          left(coalesce(geo->>'timezone', dev->>'tz'), 40), left(geo->>'country', 4), left(geo->>'region', 40), left(geo->>'city', 80),
          greatest(0, coalesce((dev->>'used_ms')::bigint, 0)), n_open, n_task, n_ok, walled, signed)
  on conflict (device_id) do update set
    last_seen    = now(),
    user_id      = coalesce(excluded.user_id, x.user_id),
    model        = coalesce(excluded.model, x.model),
    os           = coalesce(excluded.os, x.os),
    app_version  = coalesce(excluded.app_version, x.app_version),
    locale       = coalesce(excluded.locale, x.locale),
    timezone     = coalesce(excluded.timezone, x.timezone),
    country      = coalesce(excluded.country, x.country),
    region       = coalesce(excluded.region, x.region),
    city         = coalesce(excluded.city, x.city),
    used_ms      = greatest(x.used_ms, excluded.used_ms),
    opens        = x.opens + excluded.opens,
    tasks        = x.tasks + excluded.tasks,
    tasks_ok     = x.tasks_ok + excluded.tasks_ok,
    wall_at      = coalesce(x.wall_at, excluded.wall_at),
    signed_up_at = coalesce(x.signed_up_at, excluded.signed_up_at);
end $$;

-- devices that belong to the team are left out of every number
create or replace function public.pa_internal(dev public.pa_devices) returns boolean
language sql stable security definer set search_path = '' as $$
  select dev.user_id is not null and exists (select 1 from public.pa_admins a where a.user_id = dev.user_id)
$$;

-- the whole dashboard in one call. Days are India time.
create or replace function public.pa_dashboard(days int default 30, include_team boolean default false) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  tz constant text := 'Asia/Kolkata';
  today date := (now() at time zone tz)::date;
  since timestamptz := ((today - (days - 1))::timestamp at time zone tz);
  out jsonb;
begin
  if not public.pa_is_admin() then raise exception 'not allowed' using errcode = '42501'; end if;

  with dv as (select * from public.pa_devices d where include_team or not public.pa_internal(d)),
  ev as (select e.* from public.pa_events e join dv on dv.device_id = e.device_id where e.at >= since),
  ev_all as (select e.* from public.pa_events e join dv on dv.device_id = e.device_id),
  dayz as (select generate_series(today - (days - 1), today, interval '1 day')::date as day),
  daily as (
    select z.day,
      (select count(*) from dv where (dv.first_seen at time zone tz)::date = z.day) as new_devices,
      (select count(distinct ev.device_id) from ev where (ev.at at time zone tz)::date = z.day) as active,
      (select count(*) from ev where ev.name = 'signup_completed' and (ev.at at time zone tz)::date = z.day) as signups,
      (select count(*) from ev where ev.name = 'task_done' and (ev.at at time zone tz)::date = z.day) as tasks,
      (select count(*) from ev where ev.name = 'task_done' and ev.props->>'outcome' = 'ok' and (ev.at at time zone tz)::date = z.day) as tasks_ok,
      (select count(*) from ev where ev.name = 'message_sent' and (ev.at at time zone tz)::date = z.day) as messages,
      (select coalesce(sum((ev.props->>'secs')::numeric), 0) / 60 from ev where ev.name = 'session_end' and (ev.at at time zone tz)::date = z.day) as minutes
    from dayz z
  ),
  cohort as (  -- came back on a later day?
    select dv.device_id, (dv.first_seen at time zone tz)::date as d0,
      exists (select 1 from ev_all e where e.device_id = dv.device_id and e.name = 'app_open'
              and (e.at at time zone tz)::date = (dv.first_seen at time zone tz)::date + 1) as d1,
      exists (select 1 from ev_all e where e.device_id = dv.device_id and e.name = 'app_open'
              and (e.at at time zone tz)::date between (dv.first_seen at time zone tz)::date + 1 and (dv.first_seen at time zone tz)::date + 7) as w1,
      exists (select 1 from ev_all e where e.device_id = dv.device_id and e.name = 'app_open'
              and (e.at at time zone tz)::date > (dv.first_seen at time zone tz)::date) as ever
    from dv
  ),
  tasks as (select * from ev where ev.name = 'task_done')
  select jsonb_build_object(
    'generated_at', now(),
    'days', days,
    'kpi', jsonb_build_object(
      'devices_all',        (select count(*) from dv),
      'devices_new_today',  (select count(*) from dv where (dv.first_seen at time zone tz)::date = today),
      'devices_new_period', (select count(*) from dv where dv.first_seen >= since),
      'active_today',       (select count(distinct device_id) from ev where (at at time zone tz)::date = today),
      'active_7d',          (select count(distinct device_id) from ev where at >= now() - interval '7 days'),
      'active_period',      (select count(distinct device_id) from ev),
      'accounts',           (select count(*) from public.pa_profiles p where include_team or not exists (select 1 from public.pa_admins a where a.user_id = p.user_id)),
      'signups_today',      (select count(*) from ev where name = 'signup_completed' and (at at time zone tz)::date = today),
      'signups_period',     (select count(*) from ev where name = 'signup_completed'),
      'wall_devices',       (select count(*) from dv where wall_at is not null),
      'wall_converted',     (select count(*) from dv where wall_at is not null and user_id is not null),
      'tasks_today',        (select count(*) from tasks where (at at time zone tz)::date = today),
      'tasks_period',       (select count(*) from tasks),
      'tasks_ok_period',    (select count(*) from tasks where props->>'outcome' = 'ok'),
      'task_secs_median',   (select percentile_cont(0.5) within group (order by (props->>'secs')::numeric) from tasks),
      'used_min_avg',       (select round(avg(used_ms) / 60000.0, 1) from dv),
      'used_min_median',    (select round((percentile_cont(0.5) within group (order by used_ms) / 60000.0)::numeric, 1) from dv),
      'unread_messages',    (select count(*) from public.pa_messages m where not m.from_admin and m.read_at is null),
      'messages_period',    (select count(*) from ev where name = 'message_sent')
    ),
    'daily', (select jsonb_agg(to_jsonb(daily) order by day) from daily),
    'funnel', jsonb_build_array(
      jsonb_build_object('step', 'Opened the app',           'n', (select count(*) from dv)),
      jsonb_build_object('step', 'Gave the AI a task',       'n', (select count(*) from dv where tasks > 0)),
      jsonb_build_object('step', 'Reached the 2-minute wall','n', (select count(*) from dv where wall_at is not null)),
      jsonb_build_object('step', 'Made an account',          'n', (select count(*) from dv where user_id is not null)),
      jsonb_build_object('step', '5+ tasks',                 'n', (select count(*) from dv where tasks >= 5)),
      jsonb_build_object('step', 'Came back another day',    'n', (select count(*) from cohort where ever))
    ),
    'retention', (select jsonb_agg(r order by r->>'week') from (
        select jsonb_build_object('week', to_char(date_trunc('week', d0), 'DD Mon'),
          'devices', count(*), 'd1', count(*) filter (where d1), 'w1', count(*) filter (where w1)) r
        from cohort group by date_trunc('week', d0) order by date_trunc('week', d0) desc limit 8) q),
    'countries', (select jsonb_agg(x) from (select jsonb_build_object('k', coalesce(country, '?'), 'n', count(*)) x from dv group by country order by count(*) desc limit 15) q),
    'cities',    (select jsonb_agg(x) from (select jsonb_build_object('k', coalesce(city, '?') || ', ' || coalesce(country, '?'), 'n', count(*)) x from dv group by city, country order by count(*) desc limit 15) q),
    'models',    (select jsonb_agg(x) from (select jsonb_build_object('k', coalesce(model, '?'), 'n', count(*)) x from dv group by model order by count(*) desc limit 12) q),
    'os',        (select jsonb_agg(x) from (select jsonb_build_object('k', coalesce(os, '?'), 'n', count(*)) x from dv group by os order by count(*) desc limit 10) q),
    'versions',  (select jsonb_agg(x) from (select jsonb_build_object('k', coalesce(app_version, '?'), 'n', count(*)) x from dv group by app_version order by count(*) desc limit 10) q),
    'languages', (select jsonb_agg(x) from (select jsonb_build_object('k', coalesce(locale, '?'), 'n', count(*)) x from dv group by locale order by count(*) desc limit 10) q),
    'sources',   (select jsonb_agg(x) from (select jsonb_build_object('k', coalesce(nullif(props->>'source', ''), 'unknown'), 'n', count(distinct device_id)) x
                   from ev where name = 'first_open' group by coalesce(nullif(props->>'source', ''), 'unknown') order by count(distinct device_id) desc limit 12) q),
    'outcomes',  (select jsonb_agg(x) from (select jsonb_build_object('k', coalesce(props->>'outcome', '?'), 'n', count(*)) x from tasks group by props->>'outcome' order by count(*) desc) q),
    'ai_models', (select jsonb_agg(x) from (select jsonb_build_object('k', coalesce(props->>'model', '?'), 'n', count(*)) x from tasks group by props->>'model' order by count(*) desc limit 8) q),
    'sites',     (select jsonb_agg(x) from (select jsonb_build_object('k', h, 'n', count(*)) x
                   from tasks, jsonb_array_elements_text(coalesce(props->'sites', '[]'::jsonb)) h group by h order by count(*) desc limit 20) q),
    'tools',     (select jsonb_agg(x) from (select jsonb_build_object('k', t.key, 'n', sum(t.value::int)) x
                   from tasks, jsonb_each_text(coalesce(props->'tools', '{}'::jsonb)) t group by t.key order by sum(t.value::int) desc) q),
    'errors',    (select jsonb_agg(x) from (select jsonb_build_object('k', left(coalesce(props->>'error', props->>'reason', name), 120), 'n', count(*), 'last', max(at)) x
                   from ev where name in ('task_done', 'error', 'signup_failed', 'key_check') and (props ? 'error' or props ? 'reason')
                   and coalesce(props->>'outcome', 'fail') <> 'ok' group by left(coalesce(props->>'error', props->>'reason', name), 120) order by count(*) desc limit 15) q),
    'hours',     (select jsonb_agg(x order by (x->>'h')::int) from (select jsonb_build_object('h', extract(hour from at at time zone tz)::int, 'n', count(*)) x
                   from ev where name in ('app_open', 'task_started') group by extract(hour from at at time zone tz)) q),
    'tasks_recent', (select jsonb_agg(x) from (
        select jsonb_build_object('at', t.at, 'device', t.device_id, 'who', coalesce(p.name, ''), 'text', t.props->>'text',
          'outcome', t.props->>'outcome', 'steps', t.props->'steps', 'secs', t.props->'secs', 'model', t.props->>'model',
          'answer', left(t.props->>'answer', 300), 'error', t.props->>'error', 'country', t.country, 'city', t.city) x
        from tasks t left join public.pa_profiles p on p.user_id = t.user_id order by t.at desc limit 80) q)
  ) into out;
  return out;
end $$;

-- everyone who has used the app: one row per phone, with their account if they made one
create or replace function public.pa_people(include_team boolean default false) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  if not public.pa_is_admin() then raise exception 'not allowed' using errcode = '42501'; end if;
  return (select coalesce(jsonb_agg(x order by x->>'last_seen' desc), '[]'::jsonb) from (
    select jsonb_build_object(
      'device', d.device_id, 'first_seen', d.first_seen, 'last_seen', d.last_seen,
      'name', p.name, 'email', u.email, 'mobile', p.mobile, 'signed_up', d.signed_up_at,
      'country', d.country, 'region', d.region, 'city', d.city, 'model', d.model, 'os', d.os, 'version', d.app_version,
      'locale', d.locale, 'minutes', round(d.used_ms / 60000.0, 1), 'opens', d.opens, 'tasks', d.tasks, 'tasks_ok', d.tasks_ok,
      'walled', d.wall_at is not null,
      'messages', (select count(*) from public.pa_messages m where m.user_id = d.user_id and not m.from_admin)) x
    from public.pa_devices d
    left join public.pa_profiles p on p.user_id = d.user_id
    left join auth.users u on u.id = d.user_id
    where include_team or not public.pa_internal(d)
    order by d.last_seen desc limit 1000) q);
end $$;

-- live feed: the last things that happened, newest first (optionally one phone only)
create or replace function public.pa_feed(lim int default 150, one_device text default null) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  if not public.pa_is_admin() then raise exception 'not allowed' using errcode = '42501'; end if;
  return (select coalesce(jsonb_agg(x), '[]'::jsonb) from (
    select jsonb_build_object('at', e.at, 'device', e.device_id, 'who', p.name, 'name', e.name, 'props', e.props,
                              'country', e.country, 'city', e.city, 'v', e.app_version) x
    from public.pa_events e left join public.pa_profiles p on p.user_id = e.user_id
    where one_device is null or e.device_id = one_device
    order by e.id desc limit least(lim, 1000)) q);
end $$;

-- functions: closed to everyone, then opened exactly where needed
revoke all on function public.pa_ingest(jsonb) from public, anon, authenticated;
grant execute on function public.pa_ingest(jsonb) to service_role;
revoke all on function public.pa_internal(public.pa_devices) from public, anon, authenticated;
revoke all on function public.pa_dashboard(int, boolean) from public, anon, authenticated;
revoke all on function public.pa_people(boolean) from public, anon, authenticated;
revoke all on function public.pa_feed(int, text) from public, anon, authenticated;
grant execute on function public.pa_dashboard(int, boolean) to authenticated;
grant execute on function public.pa_people(boolean) to authenticated;
grant execute on function public.pa_feed(int, text) to authenticated;
