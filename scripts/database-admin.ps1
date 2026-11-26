param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('configure','provision','inspect','backup','restore')][string]$Action,
    [ValidatePattern('^[a-zA-Z][a-zA-Z0-9_]{0,39}$')][string]$Database = 'bankdirect',
    [ValidatePattern('^[a-zA-Z][a-zA-Z0-9_-]{0,39}$')][string]$LoginPath = 'smartbank-admin',
    [ValidatePattern('^[a-zA-Z][a-zA-Z0-9_]{0,31}$')][string]$AppUser = 'smartbank_app',
    [ValidatePattern('^[a-zA-Z][a-zA-Z0-9_]{0,31}$')][string]$LoginUser = 'root',
    [ValidateRange(1,65535)][int]$Port = 3306,
    [Security.SecureString]$AppPassword,
    [string]$MySqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin',
    [string]$BackupDirectory = (Join-Path $env:LOCALAPPDATA 'SmartBankingJ\backups'),
    [string]$BackupFile,
    [string]$TargetDatabase
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Windows argv quoting, without invoking a shell or interpolating SQL into a command.
function Quote-Argument([string]$Value) {
    '"' + [regex]::Replace([regex]::Replace($Value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}
function Invoke-MySql([string]$Sql, [string]$InputFile, [switch]$Sensitive) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = Join-Path $MySqlBin 'mysql.exe'
    $arguments = @("--login-path=$LoginPath", '--host=127.0.0.1', "--port=$Port", '--protocol=TCP', '--connect-timeout=5',
        '--default-character-set=utf8mb4', '--batch', '--raw', '--skip-column-names', '--binary-mode')
    if ($InputFile) { $arguments += "--database=$TargetDatabase" }
    $info.Arguments = ($arguments | ForEach-Object { Quote-Argument $_ }) -join ' '
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        $outputTask = $process.StandardOutput.ReadToEndAsync()
        $errorTask = $process.StandardError.ReadToEndAsync()
        if ($InputFile) {
            $stream = [IO.File]::OpenRead($InputFile)
            try { $stream.CopyTo($process.StandardInput.BaseStream) } finally { $stream.Dispose() }
        } else {
            $bytes = [Text.Encoding]::UTF8.GetBytes($Sql + "`n")
            $process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
        }
        $process.StandardInput.Close()
        $process.WaitForExit()
        $output = $outputTask.Result
        $failure = $errorTask.Result
        if ($process.ExitCode -ne 0) {
            if ($Sensitive) { throw 'Creation du compte echouee. Verifier le profil administrateur, la politique de mot de passe et que le compte est nouveau. Aucun SQL sensible affiche.' }
            throw "MySQL a echoue : $failure"
        }
        return $output.Trim()
    } finally { $process.Dispose() }
}
function Protect-Directory([string]$Path) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $Path '/inheritance:r' '/grant:r' "${identity}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Impossible de proteger le dossier local.' }
}
function Assert-OutsideRepository([string]$Path) {
    $resolved = [IO.Path]::GetFullPath($Path)
    $workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')).TrimEnd('\')
    if ($resolved.Equals($workspace, [StringComparison]::OrdinalIgnoreCase) -or
        $resolved.StartsWith($workspace + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Choisir un dossier de sauvegarde hors de SmartBankingJ.'
    }
    return $resolved
}

if (-not (Test-Path -LiteralPath (Join-Path $MySqlBin 'mysql.exe'))) { throw 'Client MySQL introuvable. Renseigner -MySqlBin.' }
if ($Action -eq 'configure') {
    & (Join-Path $MySqlBin 'mysql_config_editor.exe') set "--login-path=$LoginPath" --host=127.0.0.1 "--port=$Port" "--user=$LoginUser" --password
    if ($LASTEXITCODE -ne 0) { throw 'Configuration du profil MySQL echouee.' }
    Write-Output 'Profil local configure hors de Git. Il est obscurci par MySQL, pas chiffre comme un coffre-fort.'
    return
}

if ($Action -eq 'provision') {
    # CREATE USER deliberately fails for an existing account: no password rotation or grant reset by surprise.
    [void](Invoke-MySql "SELECT id FROM $Database.users LIMIT 1; SELECT token_hash FROM $Database.auth_sessions LIMIT 1; SELECT token_hash FROM $Database.password_resets LIMIT 1; SELECT id FROM $Database.auth_events LIMIT 1;")
    $secret = $AppPassword
    if ($null -eq $secret) { $secret = Read-Host "Nouveau mot de passe MySQL pour $AppUser (12 a 128 caracteres)" -AsSecureString }
    $plain = [System.Net.NetworkCredential]::new('', $secret).Password
    try {
        if ($plain.Length -lt 12 -or $plain.Length -gt 128 -or $plain -match '[\x00-\x1F\x7F]') { throw 'Mot de passe requis : 12 a 128 caracteres, sans controles.' }
        $escaped = $plain.Replace("'", "''")
        $sql = @"
SET SESSION sql_mode = 'NO_BACKSLASH_ESCAPES';
CREATE USER '$AppUser'@'localhost' IDENTIFIED BY '$escaped';
GRANT SELECT ON $Database.customers TO '$AppUser'@'localhost';
GRANT SELECT, UPDATE ON $Database.users TO '$AppUser'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON $Database.auth_sessions TO '$AppUser'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON $Database.password_resets TO '$AppUser'@'localhost';
GRANT INSERT ON $Database.auth_events TO '$AppUser'@'localhost';
SHOW GRANTS FOR '$AppUser'@'localhost';
"@
        Invoke-MySql -Sql $sql -Sensitive
        Write-Output "Compte $AppUser cree. Utiliser ce mot de passe dans SMARTBANK_DB_PASSWORD, avec SMARTBANK_DB_USER=$AppUser."
    } finally { $plain = $null; $escaped = $null; $sql = $null; $secret.Dispose() }
    return
}

if ($Action -eq 'inspect') {
    Invoke-MySql @"
SELECT CONCAT('Connexion : ', CURRENT_USER());
SELECT TABLE_NAME, ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA='$Database' ORDER BY TABLE_NAME;
SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME, NON_UNIQUE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='$Database' ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;
SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='$Database' AND REFERENCED_TABLE_NAME IS NOT NULL;
SHOW GRANTS;
"@
    return
}

if ($Action -eq 'backup') {
    $directory = Assert-OutsideRepository $BackupDirectory
    [void](New-Item -ItemType Directory -Path $directory -Force)
    Protect-Directory $directory
    $unsupported = Invoke-MySql "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$Database' AND (TABLE_TYPE <> 'BASE TABLE' OR ENGINE <> 'InnoDB');"
    if ($unsupported -ne '0') { throw 'Cette sauvegarde prend en charge uniquement les tables InnoDB sans vues.' }
    $objects = Invoke-MySql "SELECT (SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA='$Database') + (SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA='$Database') + (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='$Database');"
    if ($objects -ne '0') { throw 'Procedures, evenements ou triggers detectes : une strategie specifique est necessaire.' }
    $tables = Invoke-MySql "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='$Database' ORDER BY TABLE_NAME;"
    if (-not $tables) { throw 'Base vide ou absente : aucune sauvegarde creee.' }
    $name = $Database + '-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8) + '.sql'
    $path = Join-Path $directory $name
    $partial = $path + '.partial'
    try {
        & (Join-Path $MySqlBin 'mysqldump.exe') "--login-path=$LoginPath" --host=127.0.0.1 "--port=$Port" --protocol=TCP --default-character-set=utf8mb4 --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF --column-statistics=0 --skip-triggers --skip-add-locks --hex-blob "--result-file=$partial" $Database
        if ($LASTEXITCODE -ne 0) { throw 'Sauvegarde echouee.' }
        if ((Get-Item -LiteralPath $partial).Length -eq 0) { throw 'Sauvegarde vide.' }
        Move-Item -LiteralPath $partial -Destination $path
        $manifest = [ordered]@{ format=1; database=$Database; createdUtc=[DateTime]::UtcNow.ToString('o'); sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash; tables=@($tables -split '\r?\n') }
        [IO.File]::WriteAllText($path + '.json', ($manifest | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
        Write-Output "Sauvegarde terminee : $path"
        Write-Output 'Ne pas modifier le schema pendant la sauvegarde. Le fichier contient des donnees sensibles ; ne pas le pousser.'
    } finally { if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial } }
    return
}

if ($Action -eq 'restore') {
    if (-not $BackupFile -or -not $TargetDatabase) { throw 'Renseigner -BackupFile et -TargetDatabase.' }
    if ($TargetDatabase -notmatch '^bankdirect_restore_[a-zA-Z0-9_]{1,40}$') { throw 'La cible doit commencer par bankdirect_restore_ et etre une nouvelle base de test.' }
    $path = (Resolve-Path -LiteralPath $BackupFile).Path
    $manifest = Get-Content -LiteralPath ($path + '.json') -Raw | ConvertFrom-Json
    if ($manifest.format -ne 1 -or $manifest.database -ne $Database -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $manifest.sha256) { throw 'Manifeste ou empreinte SHA-256 invalide.' }
    # Restore only our trusted local dump, never an arbitrary SQL download. Hash is integrity, not authenticity.
    $exists = Invoke-MySql "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$TargetDatabase';"
    if ($exists -ne '0') { throw 'La base cible existe deja. Aucune donnee modifiee.' }
    [void](Invoke-MySql "CREATE DATABASE $TargetDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
    [void](Invoke-MySql -InputFile $path)
    $actual = Invoke-MySql "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='$TargetDatabase' ORDER BY TABLE_NAME;"
    $difference = @(Compare-Object @($manifest.tables) @($actual -split '\r?\n'))
    if ($difference.Count -ne 0) { throw 'Inventaire des tables restaurees different du manifeste. Base de test conservee pour diagnostic.' }
    # A restored database must never revive a previously valid login or recovery code.
    [void](Invoke-MySql "DELETE FROM $TargetDatabase.auth_sessions; DELETE FROM $TargetDatabase.password_resets;")
    $checks = Invoke-MySql "SELECT CONCAT('CHECK TABLE ', GROUP_CONCAT(CONCAT('$TargetDatabase.',TABLE_NAME) SEPARATOR ', '), ';') FROM information_schema.TABLES WHERE TABLE_SCHEMA='$TargetDatabase';"
    $result = Invoke-MySql $checks
    if ($result -match '\terror\t|\twarning\t' -or $result -notmatch '\tstatus\tOK') { throw 'Verification des tables non concluante. Base de test conservee.' }
    Write-Output $result
    Write-Output "Restauration terminee dans $TargetDatabase. Tables verifiees ; sessions et codes de recuperation invalides. Base source preservee."
}
