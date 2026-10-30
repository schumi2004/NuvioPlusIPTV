BEGIN;

-------------------------------------------------------------------------------
-- Live TV (IPTV) sync for the NuvioPlusIPTV fork.
--
-- One copy of the Live TV setup per account: playlists (including Xtream
-- logins), guides, favorites, hidden channels, channel order, sports follows
-- and Live TV settings. The client merges copies itself (three-way, per
-- source), so the server only stores and returns the latest copy.
--
-- Copy this file into the self-host checkout's database/migrations/ folder;
-- `./nuvio up` or `./nuvio migrate` applies it once. The 9xxx version keeps it
-- clear of upstream migration numbers.
-------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.iptv_sync_blobs (
    user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT iptv_sync_blobs_payload_object CHECK (jsonb_typeof(payload) = 'object')
);

ALTER TABLE public.iptv_sync_blobs ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can manage own iptv sync blob" ON public.iptv_sync_blobs;
CREATE POLICY "Users can manage own iptv sync blob" ON public.iptv_sync_blobs
    USING ((( SELECT auth.uid() AS uid) = user_id))
    WITH CHECK ((( SELECT auth.uid() AS uid) = user_id));

REVOKE ALL ON TABLE public.iptv_sync_blobs FROM PUBLIC, anon;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.iptv_sync_blobs TO authenticated;
GRANT ALL ON TABLE public.iptv_sync_blobs TO service_role;

-------------------------------------------------------------------------------
-- Client RPCs
-------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.iptv_sync_pull()
RETURNS TABLE(payload jsonb, updated_at timestamp with time zone)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, auth, extensions, pg_temp
AS $function$
    SELECT b.payload, b.updated_at
    FROM public.iptv_sync_blobs b
    WHERE b.user_id = get_sync_owner();
$function$;

CREATE OR REPLACE FUNCTION public.iptv_sync_push(p_payload jsonb)
RETURNS timestamp with time zone
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, auth, extensions, pg_temp
AS $function$
declare
    v_user_id uuid := get_sync_owner();
    v_now timestamp with time zone := now();
begin
    if p_payload is null or jsonb_typeof(p_payload) <> 'object' then
        raise exception 'Invalid Live TV payload';
    end if;
    if jsonb_typeof(p_payload->'data') is distinct from 'object' then
        raise exception 'Live TV payload has no data';
    end if;
    -- Large playlists stay on the provider; this is settings only.
    if octet_length(p_payload::text) > 8 * 1024 * 1024 then
        raise exception 'Live TV payload too large';
    end if;

    insert into public.iptv_sync_blobs (user_id, payload, updated_at)
    values (v_user_id, p_payload, v_now)
    on conflict (user_id) do update
        set payload = excluded.payload,
            updated_at = excluded.updated_at
    where public.iptv_sync_blobs.payload is distinct from excluded.payload;

    return v_now;
end;
$function$;

CREATE OR REPLACE FUNCTION public.iptv_sync_delete()
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, public, auth, extensions, pg_temp
AS $function$
    DELETE FROM public.iptv_sync_blobs WHERE user_id = get_sync_owner();
$function$;

REVOKE ALL ON FUNCTION public.iptv_sync_pull() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.iptv_sync_push(jsonb) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.iptv_sync_delete() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.iptv_sync_pull() TO authenticated;
GRANT EXECUTE ON FUNCTION public.iptv_sync_push(jsonb) TO authenticated;
GRANT EXECUTE ON FUNCTION public.iptv_sync_delete() TO authenticated;

INSERT INTO nuvio_migrations.schema_migrations (version)
VALUES ('90000000000001')
ON CONFLICT (version) DO NOTHING;

COMMIT;

NOTIFY pgrst, 'reload schema';
