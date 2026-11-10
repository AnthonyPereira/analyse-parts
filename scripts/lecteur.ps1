# Lecteur local (ADR 0019) : lit avec Ollama les rapports pas encore lus et enregistre les lectures
# directement dans la base en ligne (Neon). Ctrl+C pour arrêter : les lectures en attente sont
# écrites avant la sortie.
#
#   .\scripts\lecteur.ps1            construit l'application si besoin, puis lance le lecteur
#   .\scripts\lecteur.ps1 -Build     reconstruit l'application avant de lancer
#
# Il faut : Ollama lancé (qwen3:14b et snowflake-arctic-embed2), et dans .env à la racine du dépôt
# NEON_DIRECT_URL=postgresql://utilisateur:motdepasse@hote/base?sslmode=require (adresse directe,
# sans « -pooler »). Déployer le code en ligne avant de lancer le lecteur : il ne migre pas la base.
param([switch]$Build)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# Adresse de la base, lue dans .env
$line = Get-Content (Join-Path $root '.env') | Where-Object { $_ -match '^NEON_DIRECT_URL=' } | Select-Object -First 1
if (-not $line) { throw 'NEON_DIRECT_URL absent du fichier .env' }
$url = $line.Substring('NEON_DIRECT_URL='.Length).Trim()
if ($url -notmatch '^postgres(?:ql)?://([^:]+):([^@]+)@([^/]+)/([^?]+)') { throw "NEON_DIRECT_URL illisible" }
$env:READER_DATABASE_USER = [uri]::UnescapeDataString($Matches[1])
$env:READER_DATABASE_PASSWORD = [uri]::UnescapeDataString($Matches[2])
$env:READER_DATABASE_URL = "jdbc:postgresql://$($Matches[3])/$($Matches[4])?sslmode=require"
$env:SPRING_PROFILES_ACTIVE = 'reader'

# Java 27 : JAVA_HOME s'il est défini, sinon le JDK installé par IntelliJ
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Join-Path $HOME '.jdks\openjdk-27.0.1' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'

$backend = Join-Path $root 'backend'
$jar = Join-Path $backend 'target\backend-0.0.1-SNAPSHOT.jar'
if ($Build -or -not (Test-Path $jar)) {
    Push-Location $backend
    try { & .\mvnw.cmd -q -DskipTests package; if ($LASTEXITCODE -ne 0) { throw 'construction en échec' } }
    finally { Pop-Location }
}

Write-Host "Lecteur local branché sur $($Matches[3])"
# Java écrit des avertissements sur sa sortie d'erreur : avec « Stop », PowerShell 5.1 les prendrait
# pour des erreurs et couperait le lecteur.
$ErrorActionPreference = 'Continue'
& $java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -jar $jar
exit $LASTEXITCODE
