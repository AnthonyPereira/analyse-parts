-- Importe les lectures exportées par scripts/publier-lectures.ps1 (ADR 0023), lancé sur le serveur
-- par le workflow « Lectures vers le serveur ». Les sociétés sont retrouvées par ISIN. Une lecture
-- déjà présente n'est remplacée que si celle du PC est plus récente, ou classée alors qu'elle ne
-- l'était pas : relancer l'import ne change rien.
\set ON_ERROR_STOP on
begin;

create temp table lecture (
    isin          text,
    source_id     text,
    period_end    date,
    model         varchar(100),
    created_at    timestamptz,
    duration_ms   bigint,
    passage_count integer,
    rejected      integer,
    content       jsonb,
    ranked_at     timestamptz
) on commit drop;

\copy lecture from '/tmp/lectures.csv' with (format csv, header true)

with ecrites as (
    insert into report_analysis (company_id, source_id, period_end, model, created_at, duration_ms,
                                 passage_count, rejected, content, ranked_at)
    select c.id, l.source_id, l.period_end, l.model, l.created_at, l.duration_ms,
           l.passage_count, l.rejected, l.content, l.ranked_at
    from lecture l
    join company c on c.isin = l.isin
    on conflict (company_id, source_id) do update set
        period_end    = excluded.period_end,
        model         = excluded.model,
        created_at    = excluded.created_at,
        duration_ms   = excluded.duration_ms,
        passage_count = excluded.passage_count,
        rejected      = excluded.rejected,
        content       = excluded.content,
        ranked_at     = excluded.ranked_at
    where excluded.created_at > report_analysis.created_at
       or (report_analysis.ranked_at is null and excluded.ranked_at is not null)
    returning (xmax = 0) as ajoutee
)
select (select count(*) from lecture)                                          as lectures_recues,
       (select count(*) from lecture l where not exists
            (select 1 from company c where c.isin = l.isin))                   as societes_inconnues,
       count(*) filter (where ajoutee)                                         as ajoutees,
       count(*) filter (where not ajoutee)                                     as remplacees
from ecrites;

commit;

select count(*) as lectures_sur_le_serveur from report_analysis;
