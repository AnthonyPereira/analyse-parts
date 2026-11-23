# Copie les lectures du modèle de langage de la base locale vers la base du serveur maison (ADR 0023).
#
#   .\scripts\publier-lectures.ps1
#
# 1. exporte toutes les lectures de la base locale (docker compose de backend/), ISIN à la place de
#    l'identifiant interne de la société ;
# 2. les dépose dans une release temporaire du dépôt (privé) ;
# 3. lance le workflow « Lectures vers le serveur », qui les importe sur le portable, et attend la fin ;
# 4. supprime la release, réussite ou non.
# Relancer ne crée pas de doublon : une lecture déjà sur le serveur n'est remplacée que si elle est
# plus récente sur le PC. Il faut : Docker (base locale lancée) et gh connecté au dépôt.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$work = Join-Path ([IO.Path]::GetTempPath()) ("lectures-" + [guid]::NewGuid())
New-Item -ItemType Directory $work | Out-Null
$tag = 'lectures-' + (Get-Date -Format 'yyyyMMdd-HHmmss')

try {
    $db = docker ps -q --filter label=com.docker.compose.project=backend --filter label=com.docker.compose.service=postgres
    if (-not $db) { throw 'base locale arrêtée : docker compose up -d dans backend/' }
    $sql = "\copy (select c.isin, r.source_id, r.period_end, r.model, r.created_at, r.duration_ms, " +
           "r.passage_count, r.rejected, r.content, r.ranked_at from report_analysis r " +
           "join company c on c.id = r.company_id order by c.isin) to '/tmp/lectures.csv' with (format csv, header true)"
    docker exec $db psql -v ON_ERROR_STOP=1 -U analyseparts -d analyseparts -c $sql
    if ($LASTEXITCODE -ne 0) { throw 'export en échec' }
    docker exec $db gzip -f /tmp/lectures.csv
    docker cp "${db}:/tmp/lectures.csv.gz" (Join-Path $work 'lectures.csv.gz')
    docker exec $db rm -f /tmp/lectures.csv.gz
    $size = [math]::Round((Get-Item (Join-Path $work 'lectures.csv.gz')).Length / 1MB, 1)
    Write-Host "Lectures exportées ($size Mo), release $tag"

    Push-Location $root
    try {
        gh release create $tag (Join-Path $work 'lectures.csv.gz') --prerelease --title $tag --notes 'Transfert temporaire des lectures (supprimé après import)'
        if ($LASTEXITCODE -ne 0) { throw 'création de la release en échec' }
        gh workflow run lectures.yml --ref main -f tag=$tag
        if ($LASTEXITCODE -ne 0) { throw 'lancement du workflow en échec' }
        Start-Sleep -Seconds 5
        $run = gh run list --workflow lectures.yml --limit 1 --json databaseId --jq '.[0].databaseId'
        gh run watch $run --exit-status
        $ok = $LASTEXITCODE -eq 0
        gh run view $run --log | Select-String 'lectures_recues|lectures_sur_le_serveur' -Context 0,2 | Out-Host
        if (-not $ok) { throw "import en échec : gh run view $run --log" }
    } finally {
        gh release delete $tag --yes --cleanup-tag 2>$null
        Pop-Location
    }
} finally {
    Remove-Item -Recurse -Force $work
}
