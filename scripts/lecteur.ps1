# Lecteur local (ADR 0019, 0023) : lit avec Ollama les rapports pas encore lus et enregistre les
# lectures dans la base LOCALE (docker compose de backend/, port 5433). Ctrl+C pour arrêter : les
# lectures en attente sont écrites avant la sortie. Ensuite, scripts/publier-lectures.ps1 les copie
# sur le serveur maison.
#
#   .\scripts\lecteur.ps1            construit l'application si besoin, puis lance le lecteur
#   .\scripts\lecteur.ps1 -Build     reconstruit l'application avant de lancer
#
# Il faut : Ollama lancé (qwen3:14b et snowflake-arctic-embed2) et la base locale lancée, migrée
# (le lecteur ne migre pas : lancer une fois l'application en développement après une mise à jour).
param([switch]$Build)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# Base locale de développement (backend/compose.yaml)
$env:READER_DATABASE_URL = 'jdbc:postgresql://localhost:5433/analyseparts'
$env:READER_DATABASE_USER = 'analyseparts'
$env:READER_DATABASE_PASSWORD = 'analyseparts'
# Une lecture écrite dès qu'elle est finie : la base locale ne dort pas, rien à ménager
if (-not $env:READER_SAVE_BATCH) { $env:READER_SAVE_BATCH = '1' }
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

Write-Host "Lecteur local branché sur $env:READER_DATABASE_URL"
# Java écrit des avertissements sur sa sortie d'erreur : avec « Stop », PowerShell 5.1 les prendrait
# pour des erreurs et couperait le lecteur.
$ErrorActionPreference = 'Continue'
& $java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -jar $jar
exit $LASTEXITCODE
