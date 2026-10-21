[CmdletBinding()]
param(
    [string]$DatabaseName = 'community_residence',
    [string]$HostName = 'localhost',
    [int]$Port = 3306,
    [switch]$SkipBackup
)

$ErrorActionPreference = 'Stop'

if ($DatabaseName -notmatch '^[A-Za-z0-9_]+$') {
    throw 'DatabaseName 只允许字母、数字和下划线。'
}

if ($HostName -notin @('localhost', '127.0.0.1', '::1')) {
    throw "为避免误操作，重置脚本只允许本机数据库地址，当前为: $HostName"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$envFile = Join-Path $repoRoot '.env'

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "缺少本地凭据文件: $envFile。请先复制 .env.example 为 .env 并填写 DB_USERNAME/DB_PASSWORD。"
}

function Get-DotEnvValue([string]$Name) {
    $line = Get-Content -LiteralPath $envFile |
        Where-Object { $_ -match "^\s*$([regex]::Escape($Name))\s*=" } |
        Select-Object -First 1
    if ($null -eq $line) {
        return $null
    }
    return ($line -replace "^\s*$([regex]::Escape($Name))\s*=\s*", '')
}

$dbUser = Get-DotEnvValue 'DB_USERNAME'
$dbPassword = Get-DotEnvValue 'DB_PASSWORD'
if ([string]::IsNullOrWhiteSpace($dbUser)) {
    throw 'DB_USERNAME 未配置。'
}

$mysql = (Get-Command mysql -ErrorAction Stop).Source
$mysqldump = (Get-Command mysqldump -ErrorAction Stop).Source
$backupDir = Join-Path $repoRoot 'output\db-backups'
$backupPath = Join-Path $backupDir ("{0}-before-reset-{1}.sql" -f $DatabaseName, (Get-Date -Format 'yyyyMMdd-HHmmss'))

$env:MYSQL_PWD = $dbPassword
try {
    if (-not $SkipBackup) {
        New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
        & $mysqldump "--host=$HostName" "--port=$Port" "--user=$dbUser" '--protocol=tcp' '--single-transaction' '--set-gtid-purged=OFF' '--routines' '--events' '--triggers' '--databases' $DatabaseName "--result-file=$backupPath"
        if ($LASTEXITCODE -ne 0) {
            throw 'mysqldump 失败，未执行数据库重置。'
        }
        Write-Host "已生成本地备份: $backupPath"
    }

    $resetSql = "DROP DATABASE IF EXISTS $DatabaseName; CREATE DATABASE $DatabaseName DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    & $mysql "--host=$HostName" "--port=$Port" "--user=$dbUser" '--protocol=tcp' '--batch' '--skip-column-names' '--execute' $resetSql
    if ($LASTEXITCODE -ne 0) {
        throw '重建数据库失败。'
    }

    Write-Host "已重建本机数据库: $DatabaseName"
    Write-Host '下一步：启动后端或 Compose backend，让 Flyway 自动执行 V1~V45 并写入演示种子。'
    Write-Host '宿主机 uploads/ 未删除，图片资源仍只保留在本地。'
}
finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
