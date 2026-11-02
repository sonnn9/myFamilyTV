<#
.SYNOPSIS
  Makes a TV-friendly copy of a FamilyTV folder: every video becomes MP4 (H.264 + AAC, max 1080p).

.DESCRIPTION
  Many downloaded videos use AV1 or VP9, which older TVs (e.g. Sony BRAVIA VU1) cannot decode:
  only the sound plays. This script never modifies the source folder. It mirrors it into
  -Output: compatible videos (H.264, AAC/MP3, yuv420p, <= 1080p) and all other files
  (config.json, .jpg thumbnails) are copied, other videos are re-encoded with ffmpeg.
  Running it again only handles new or changed files, so it can be stopped and resumed. Files
  that are still downloading (yt-dlp temp files, or changed in the last 2 minutes) are left
  for the next run.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\convert_for_tv.ps1 -Source E:\FamilyTV -Output D:\FamilyTV_tv
#>
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [string]$Output
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false } catch { }   # keeps Vietnamese in a redirected log
foreach ($tool in 'ffmpeg', 'ffprobe') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Cần cài $tool (ví dụ: winget install Gyan.FFmpeg)." }
}
$Source = (Resolve-Path $Source).Path.TrimEnd('\')
if (-not $Output) { $Output = "$Source" + "_tv" }
New-Item -ItemType Directory -Force $Output | Out-Null
$Output = (Resolve-Path $Output).Path.TrimEnd('\')
if ($Output.StartsWith($Source + '\')) { throw "Thư mục Output không được nằm trong Source." }

# Encoding takes hours: keep the PC usable (ffmpeg inherits this priority).
(Get-Process -Id $PID).PriorityClass = 'BelowNormal'

$videoExt = '.mp4', '.m4v', '.mkv', '.webm', '.mov', '.3gp', '.ts', '.m2ts', '.mts', '.avi', '.mpg', '.mpeg', '.flv', '.wmv'
$downloadingExt = '.part', '.ytdl', '.tmp'

# Returns @{ codec_name = ...; height = ... } (ffprobe prints fields in its own order, not the requested one).
function Probe([string]$file, [string]$stream, [string]$entries) {
    $ErrorActionPreference = 'Continue'   # Windows PowerShell turns redirected native stderr into errors
    $info = @{}
    & ffprobe -v error -select_streams $stream -show_entries "stream=$entries" -of default=noprint_wrappers=1 -- "$file" 2>$null |
        ForEach-Object { $k, $val = $_ -split '=', 2; if ($k -and -not $info.ContainsKey($k)) { $info[$k] = "$val".Trim() } }
    return $info
}

function IsUpToDate($src, [string]$dest) {
    (Test-Path -LiteralPath $dest) -and ((Get-Item -LiteralPath $dest).LastWriteTime -ge $src.LastWriteTime)
}

$files = @(Get-ChildItem -LiteralPath $Source -Recurse -File)
$converted = 0; $copied = 0; $existing = 0; $pending = 0; $failed = 0; $i = 0
foreach ($f in $files) {
    $i++
    $rel = $f.FullName.Substring($Source.Length + 1)
    $ext = $f.Extension.ToLower()
    if ($f.Name.StartsWith('.')) { continue }   # macOS "._" files etc.
    if (($downloadingExt -contains $ext) -or ($f.Name -match '\.(temp|f\d+)\.\w+$') -or ($f.LastWriteTime -gt (Get-Date).AddMinutes(-2))) {
        Write-Host "Đang tải, để lần sau: $rel"
        $pending++
        continue
    }

    if (-not ($videoExt -contains $ext)) {
        $dest = Join-Path $Output $rel
        if (IsUpToDate $f $dest) { $existing++; continue }
        New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
        Copy-Item -LiteralPath $f.FullName -Destination $dest -Force
        $copied++
        continue
    }

    $dest = Join-Path $Output ([System.IO.Path]::ChangeExtension($rel, '.mp4'))
    if (IsUpToDate $f $dest) { $existing++; continue }
    $v = Probe $f.FullName 'v:0' 'codec_name,pix_fmt,height'
    $a = [string](Probe $f.FullName 'a:0' 'codec_name').codec_name
    $height = 0; [int]::TryParse([string]$v.height, [ref]$height) | Out-Null
    $okVideo = ($v.codec_name -eq 'h264') -and ($v.pix_fmt -eq 'yuv420p') -and ($height -le 1080)
    $okAudio = ($a -eq '') -or ($a -eq 'aac') -or ($a -eq 'mp3')
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    $stamp = "$(Get-Date -Format HH:mm) [$i/$($files.Count)]"

    if ($okVideo -and $okAudio -and $ext -eq '.mp4') {
        Copy-Item -LiteralPath $f.FullName -Destination $dest -Force
        Write-Host "$stamp Giữ nguyên  : $rel"
        $copied++
        continue
    }

    Write-Host "$stamp Chuyển đổi  : $rel  (hình: $($v.codec_name) $($height)p, tiếng: $a)"
    # Encode into a temp file so a stopped run never leaves a half-written .mp4 that looks finished.
    $tmp = "$dest.part"
    $audioArgs = if ($a -eq 'aac' -or $a -eq 'mp3') { @('-c:a', 'copy') } else { @('-c:a', 'aac', '-b:a', '160k') }
    $ffArgs = @('-loglevel', 'error', '-y', '-i', $f.FullName, '-map', '0:v:0', '-map', '0:a:0?',
        '-c:v', 'libx264', '-preset', 'medium', '-crf', '21', '-profile:v', 'high', '-level:v', '4.1',
        '-pix_fmt', 'yuv420p', '-vf', "scale='min(1920,iw)':-2") + $audioArgs + @('-movflags', '+faststart', '-f', 'mp4', $tmp)
    $ErrorActionPreference = 'Continue'
    & ffmpeg @ffArgs
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -eq 0) {
        Move-Item -LiteralPath $tmp -Destination $dest -Force
        $converted++
    } else {
        Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
        Write-Warning "Lỗi khi chuyển: $rel"
        $failed++
    }
}

Write-Host ""
Write-Host "Xong. Đã chuyển: $converted, chép nguyên: $copied, đã có từ lần trước: $existing, đang tải (chạy lại sau): $pending, lỗi: $failed"
Write-Host "Thư mục kết quả: $Output  (chép thư mục này lên USB thay cho FamilyTV cũ)"
