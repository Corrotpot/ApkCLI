# Screenshots of every prototype route, for reviewing layout.
#
#   pwsh -File tools/shoot_design.ps1 [-Width 412] [-Height 915]
#
# Writes %TEMP%\apkcli-shots\<route>.png at the given dp size and x2-<route>.png
# upscaled for reading. The page is loaded with ?shot, which pins the screen to
# --dw x --dh: headless Chrome lays out at its own width (504 here, whatever
# --window-size says), so without the flag every capture is the wrong size.
param(
  [int]$Width = 412,
  [int]$Height = 915,
  [string]$Html = "$PSScriptRoot\..\build\design\ApkCLI-design.html"
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
$shots = "$env:TEMP\apkcli-shots"
$url = 'file:///' + ((Resolve-Path $Html).Path -replace '\\', '/')

New-Item -ItemType Directory -Force -Path $shots | Out-Null
Get-ChildItem $shots -File -ErrorAction SilentlyContinue | Remove-Item -Force

$routes = [ordered]@{
  '01-home'     = '/home'
  '02-first'    = '/home?first'
  '03-create'   = '/home/create'
  '04-clone'    = '/home/clone'
  '05-open'     = '/open'
  '06-settings' = '/settings'
  '07-agent'    = '/ws/telegram-bot/agent'
  '08-empty'    = '/ws/scraper/agent'
  '09-approval' = '/ws/telegram-bot/agent/approval'
  '10-changes'  = '/ws/telegram-bot/agent/changes'
  '11-memory'   = '/ws/telegram-bot/agent/memory'
  '12-model'    = '/ws/telegram-bot/agent/model'
  '13-files'    = '/ws/telegram-bot/files'
  '14-editor'   = '/ws/telegram-bot/editor'
  '15-terminal' = '/ws/telegram-bot/terminal'
  '16-checkpoint' = '/ws/telegram-bot/agent/checkpoint'
  '17-history'  = '/ws/telegram-bot/agent/history'
  '18-history-new' = '/ws/scraper/agent/history'
  '19-chat-delete' = '/ws/telegram-bot/agent/chat-delete'
  '20-chat-rename' = '/ws/telegram-bot/agent/chat-rename'
  '21-find'     = '/ws/telegram-bot/agent?find=%D1%82%D0%BE%D0%BA%D0%B5%D0%BD'
  '22-find-code' = '/ws/telegram-bot/agent?find=load_dotenv'
  '23-mode'     = '/ws/telegram-bot/agent/agent'
  '24-sub-open' = '/ws/telegram-bot/agent?sub=open'
  '25-sub-live' = '/ws/telegram-bot/agent?sub=live'
  '26-sub-live-open' = '/ws/telegram-bot/agent?sub=live-open'
  '27-sub-writing' = '/ws/telegram-bot/agent?sub=writing'
  '28-sub-ask'  = '/ws/telegram-bot/agent/approval?sub'
  '29-find-sub' = '/ws/telegram-bot/agent?find=include_router'
  '30-cmd-menu' = '/ws/telegram-bot/agent?compact=menu'
  '31-cmd-typed' = '/ws/telegram-bot/agent?compact=typed'
  '32-compacting' = '/ws/telegram-bot/agent?compact=run'
  '33-compacted' = '/ws/telegram-bot/agent?compact=done'
  '34-digest'   = '/ws/telegram-bot/agent?compact=open'
  '35-compact-failed' = '/ws/telegram-bot/agent?compact=failed'
  '36-compact-auto' = '/ws/telegram-bot/agent?compact=auto'
  '37-cmd-empty' = '/ws/scraper/agent?compact=menu'
  '38-context'  = '/ws/telegram-bot/agent/context'
  '39-find-digest' = '/ws/telegram-bot/agent?compact=open&find=BOT_TOKEN'
}

foreach ($name in $routes.Keys) {
  $route = $routes[$name]
  $flag = if ($route.Contains('?')) { '&shot' } else { '?shot' }
  if ($Width -eq 360) { $flag += '&w360' }
  # The size must be one quoted token: an unquoted comma makes PowerShell
  # split the argument in two.
  & $chrome --headless=new --hide-scrollbars "--window-size=$($Width + 48),$($Height + 48)" `
    --virtual-time-budget=2500 "--screenshot=$shots\$name.png" "$url#$route$flag" 2>$null | Out-Null
}

foreach ($file in @(Get-ChildItem "$shots\*.png" | Where-Object { $_.Name -notlike 'x2-*' })) {
  $src = [System.Drawing.Bitmap]::new($file.FullName)
  $dst = [System.Drawing.Bitmap]::new($Width * 2, $Height * 2)
  $g = [System.Drawing.Graphics]::FromImage($dst)
  $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $g.DrawImage($src,
    (New-Object System.Drawing.Rectangle 0, 0, ($Width * 2), ($Height * 2)),
    (New-Object System.Drawing.Rectangle 0, 0, $Width, $Height),
    [System.Drawing.GraphicsUnit]::Pixel)
  $g.Dispose(); $src.Dispose()
  $dst.Save((Join-Path $shots "x2-$($file.Name)"), [System.Drawing.Imaging.ImageFormat]::Png)
  $dst.Dispose()
}

"$((Get-ChildItem "$shots\x2-*.png").Count) screens in $shots"
