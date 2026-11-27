param(
    [ValidateSet('serve','check','migrate','init-demo','test')][string]$Action = 'serve',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Libraries = $env:SMARTBANK_AUTH_LIBS,
    [switch]$LocalRecovery,
    [switch]$EmailRecovery
)
$ErrorActionPreference = 'Stop'
if ($LocalRecovery -and $EmailRecovery) { throw 'Choisir LocalRecovery ou EmailRecovery, pas les deux.' }
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) {
    $jdk = Get-ChildItem (Join-Path $env:ProgramFiles 'Java') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { $JavaHome = $jdk.FullName }
}
if (-not $JavaHome) { throw 'Un JDK 17 ou superieur est necessaire.' }
$env:JAVA_HOME = $JavaHome
$env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
foreach ($name in @('SMARTBANK_DB_URL','SMARTBANK_DB_USER','SMARTBANK_DB_PASSWORD','SMARTBANK_DEMO_PASSWORD','SMARTBANK_RECOVERY_OUTBOX','SMARTBANK_RECOVERY_MODE','SMARTBANK_SMTP_HOST','SMARTBANK_SMTP_PORT','SMARTBANK_SMTP_USER','SMARTBANK_SMTP_PASSWORD','SMARTBANK_SMTP_FROM','SMARTBANK_SMTP_SECURITY')) {
    if (-not [Environment]::GetEnvironmentVariable($name,'Process')) {
        $value = [Environment]::GetEnvironmentVariable($name,'User')
        if ($null -ne $value) { [Environment]::SetEnvironmentVariable($name,$value,'Process') }
    }
}
if ($LocalRecovery) {
    $env:SMARTBANK_RECOVERY_MODE = 'local'
    $env:SMARTBANK_RECOVERY_OUTBOX = Join-Path $env:LOCALAPPDATA 'SmartBankingJ\recovery-outbox'
}
if ($EmailRecovery) { $env:SMARTBANK_RECOVERY_MODE = 'smtp' }
if (-not $Libraries) {
    $candidate = Join-Path $env:LOCALAPPDATA 'SmartBankingJ\tools\auth-libs'
    if (Test-Path -LiteralPath $candidate) { $Libraries = $candidate }
}
Push-Location (Join-Path $PSScriptRoot '..')
try {
    if ($Libraries) {
        $jars = @(Get-ChildItem -LiteralPath $Libraries -Filter '*.jar' | ForEach-Object FullName)
        if ($jars.Count -eq 0) { throw 'Le dossier Libraries doit contenir les dependances annoncees dans pom.xml.' }
        if (-not ($jars | Where-Object { [IO.Path]::GetFileName($_) -like 'jakarta.mail-*.jar' })) {
            throw 'Dependance SMTP manquante dans Libraries : Jakarta Mail 2.0.5 (et Activation). Voir README ou utiliser Maven avec un dossier Libraries complet.'
        }
        $classpath = $jars -join ';'
        $java = Join-Path $JavaHome 'bin\java.exe'
        $javac = Join-Path $JavaHome 'bin\javac.exe'
        New-Item -ItemType Directory -Force target\classes | Out-Null
        $sources = @(Get-ChildItem src\main\java -Filter '*.java' -Recurse | ForEach-Object FullName)
        & $javac --release 17 -encoding UTF-8 -cp $classpath -d target\classes @sources
        if ($LASTEXITCODE -ne 0) { throw 'Compilation backend echouee.' }
        Copy-Item src\main\resources\* target\classes -Recurse -Force
        $runtime = (Join-Path (Get-Location).Path 'target\classes') + ';' + $classpath
        if ($Action -eq 'test') {
            New-Item -ItemType Directory -Force target\test-classes | Out-Null
            $testSources = @(Get-ChildItem src\test\java -Filter '*.java' -Recurse | ForEach-Object FullName)
            & $javac --release 17 -encoding UTF-8 -cp $runtime -d target\test-classes @testSources
            if ($LASTEXITCODE -ne 0) { throw 'Compilation des tests echouee.' }
            $testRuntime = $runtime + ';' + (Join-Path (Get-Location).Path 'target\test-classes')
            & $java -cp $testRuntime org.junit.platform.console.ConsoleLauncher execute --scan-classpath --disable-banner
        } elseif ($Action -eq 'serve') { & $java -cp $runtime ma.bankdirect.api.AuthServer }
        else { & $java -cp $runtime ma.bankdirect.db.DatabaseSetup ("--" + $Action) }
    } else {
        $maven = Get-Command mvn.cmd -ErrorAction Stop
        if ($Action -eq 'test') { & $maven.Source test }
        elseif ($Action -eq 'serve') { & $maven.Source compile exec:java }
        else { & $maven.Source compile exec:java '-Dexec.mainClass=ma.bankdirect.db.DatabaseSetup' ("-Dexec.args=--" + $Action) }
    }
    if ($LASTEXITCODE -ne 0) { throw 'La commande backend a echoue.' }
} finally { Pop-Location }
