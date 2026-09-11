param(
    [Parameter(Mandatory = $true)]
    [string]$EnvFile,
    [string]$CommandScript
)

$ErrorActionPreference = 'Stop'
if (-not $CommandScript) {
    $CommandScript = Join-Path $PSScriptRoot 'set-telegram-webhook.sh'
}

function Read-DotEnv([string]$Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $name, $value = $line -split '=', 2
        $values[$name.Trim()] = $value.Trim().Trim('"').Trim("'")
    }
    return $values
}

function Invoke-Telegram([string]$Method, [hashtable]$Body = @{}) {
    try {
        return Invoke-RestMethod -Method Post -Uri "$script:ApiUrl/$Method" -Body $Body
    } catch {
        throw "Telegram configuration request failed for $Method"
    }
}

$environment = Read-DotEnv $EnvFile
$token = $environment['TELEGRAM_BOT_TOKEN']
$webhookSecret = $environment['TELEGRAM_WEBHOOK_SECRET']
$publicBaseUrl = if ($environment['PUBLIC_BASE_URL']) { $environment['PUBLIC_BASE_URL'].TrimEnd('/') } else { 'https://frontline-nations.tg-games.com' }
if (-not $token -or -not $webhookSecret) { throw 'Required Telegram settings are missing from the environment file' }
$script:ApiUrl = "https://api.telegram.org/bot$token"

$commandSets = @{}
foreach ($line in Get-Content -LiteralPath $CommandScript -Encoding UTF8) {
    if ($line -match "^commands_([a-z]+)='(.+)'$") {
        $language = $Matches[1]
        $parsedCommands = $Matches[2] | ConvertFrom-Json
        $commandSets[$language] = [object[]]$parsedCommands
    }
}

$extraDescriptions = @'
{"es":["Clasificaciones","Gu\u00eda del juego"],"pt":["Classifica\u00e7\u00f5es","Guia do jogo"],"ar":["\u0627\u0644\u062a\u0635\u0646\u064a\u0641\u0627\u062a","\u062f\u0644\u064a\u0644 \u0627\u0644\u0644\u0639\u0628\u0629"],"id":["Peringkat","Panduan game"],"hi":["\u0930\u0948\u0902\u0915\u093f\u0902\u0917","\u0917\u0947\u092e \u0917\u093e\u0907\u0921"],"tr":["S\u0131ralamalar","Oyun rehberi"]}
'@ | ConvertFrom-Json
foreach ($language in @('es', 'pt', 'ar', 'id', 'hi', 'tr')) {
    if (-not ($commandSets[$language].command -contains 'rankings')) {
        $commandSets[$language] += [pscustomobject]@{ command = 'rankings'; description = $extraDescriptions.$language[0] }
        $commandSets[$language] += [pscustomobject]@{ command = 'guide'; description = $extraDescriptions.$language[1] }
    }
}

$languages = @('en', 'ru', 'es', 'pt', 'ar', 'id', 'hi', 'tr')
foreach ($language in $languages) {
    $catalogCount = if ($commandSets.ContainsKey($language)) { $commandSets[$language].Count } else { 0 }
    if ($catalogCount -ne 16) {
        throw "Invalid command catalog for language $language (found $catalogCount, expected 16)"
    }
}

$defaultCommands = $commandSets['en'] | ConvertTo-Json -Compress -Depth 4
Invoke-Telegram 'setMyCommands' @{ commands = $defaultCommands } | Out-Null
foreach ($language in $languages) {
    Invoke-Telegram 'setMyCommands' @{
        commands = ($commandSets[$language] | ConvertTo-Json -Compress -Depth 4)
        language_code = $language
    } | Out-Null
}

$webhookUrl = "$publicBaseUrl/bot"
Invoke-Telegram 'setWebhook' @{
    url = $webhookUrl
    secret_token = $webhookSecret
    allowed_updates = '["message","callback_query"]'
} | Out-Null

$webhookInfo = Invoke-Telegram 'getWebhookInfo'
if (-not $webhookInfo.ok -or $webhookInfo.result.url -ne $webhookUrl) {
    throw 'Telegram webhook verification failed'
}
foreach ($language in $languages) {
    $menu = Invoke-Telegram 'getMyCommands' @{ language_code = $language }
    $names = @($menu.result | ForEach-Object { $_.command })
    if (-not $menu.ok -or $names.Count -ne 16 -or
        -not ($names -contains 'rankings') -or -not ($names -contains 'guide')) {
        throw "Telegram command menu verification failed for language $language"
    }
}

Write-Output 'Telegram webhook and 8 localized command menus updated and verified.'
