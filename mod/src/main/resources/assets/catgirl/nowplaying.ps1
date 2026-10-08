# Catgirl Client "Now playing": prints what Windows says is playing (Spotify, YouTube Music,
# browsers, ...) as one JSON line twice a second. Uses Windows' own media controls, so no logins.
# Album art is sent once per song, as a 64x64 PNG (base64).
param([string]$TestArt = '')
$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName System.Drawing

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
  $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]
function Await($op, [Type]$type) {
  $task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
  if (-not $task.Wait(3000)) { return $null }
  return $task.Result
}

$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime]
$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
if (-not $mgr) { [Console]::Out.WriteLine('{"error":"no media controls"}'); exit 1 }

$script:artError = ''
# $ref is anything with OpenReadAsync() (the song's thumbnail, or a file when testing).
function Get-Art($ref) {
  try {
    if (-not $ref) { $script:artError = 'no thumbnail'; return $null }
    $st = Await ($ref.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
    if (-not $st) { $script:artError = 'could not open thumbnail'; return $null }
    # Read the whole stream (some apps report a size of 0, so don't rely on it).
    $bytes = $null
    try {
      $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($st.GetInputStreamAt(0))
      $ms = New-Object System.IO.MemoryStream
      $net.CopyTo($ms)
      $bytes = $ms.ToArray()
    } catch {}
    if ((-not $bytes -or $bytes.Length -eq 0) -and $st.Size -gt 0) {
      $reader = [Windows.Storage.Streams.DataReader]::new($st.GetInputStreamAt(0))
      $n = Await ($reader.LoadAsync([uint32]$st.Size)) ([uint32])
      if ($n) { $bytes = New-Object byte[] $n; $reader.ReadBytes($bytes) }
    }
    if (-not $bytes -or $bytes.Length -eq 0) { $script:artError = 'empty thumbnail'; return $null }
    $img = [System.Drawing.Image]::FromStream((New-Object System.IO.MemoryStream(, $bytes)))
    $side = [Math]::Min($img.Width, $img.Height)
    $bmp = New-Object System.Drawing.Bitmap 64, 64
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $src = New-Object System.Drawing.Rectangle ([int](($img.Width - $side) / 2)), ([int](($img.Height - $side) / 2)), $side, $side
    $g.DrawImage($img, (New-Object System.Drawing.Rectangle 0, 0, 64, 64), $src, [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
    $out = New-Object System.IO.MemoryStream
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose(); $img.Dispose()
    return [Convert]::ToBase64String($out.ToArray())
  } catch { $script:artError = $_.Exception.Message; return $null }
}

if ($TestArt) {
  $null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
  $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($TestArt)) ([Windows.Storage.StorageFile])
  $art = Get-Art $file
  if ($art) { [Console]::Out.WriteLine("art ok, $($art.Length) chars") } else { [Console]::Out.WriteLine("art failed: $script:artError") }
  exit 0
}

$lastKey = ''
$artTries = 0
while ($true) {
  $line = @{ none = $true }
  try {
    $s = $mgr.GetCurrentSession()
    if ($s) {
      $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
      if ($p -and $p.Title) {
        $tl = $s.GetTimelineProperties()
        $pb = $s.GetPlaybackInfo()
        # Apps only tell Windows the position now and then, along with when they did; move it on to "now".
        $pos = $tl.Position.TotalSeconds
        $isPlaying = ($pb.PlaybackStatus -eq [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionPlaybackStatus]::Playing)
        if ($isPlaying -and $tl.LastUpdatedTime.Year -gt 2000) {
          $age = ([DateTimeOffset]::Now - $tl.LastUpdatedTime).TotalSeconds
          if ($age -gt 0 -and $age -lt 3600) { $pos += $age }
        }
        $line = @{
          title   = [string]$p.Title
          artist  = [string]$p.Artist
          album   = [string]$p.AlbumTitle
          app     = [string]$s.SourceAppUserModelId
          pos     = [Math]::Round($pos, 2)
          dur     = [Math]::Round(($tl.EndTime - $tl.StartTime).TotalSeconds, 2)
          playing = $isPlaying
        }
        $key = "$($p.Title)|$($p.Artist)"
        if ($key -ne $lastKey) {
          # The art can show up a moment after the song changes, so try a few times.
          $art = Get-Art $p.Thumbnail
          if ($art) { $line.art = $art; $lastKey = $key; $artTries = 0 }
          elseif (++$artTries -ge 3) { $line.art = ''; $line.artError = $script:artError; $lastKey = $key; $artTries = 0 }
        }
      }
    }
  } catch {}
  [Console]::Out.WriteLine(($line | ConvertTo-Json -Compress))
  [Console]::Out.Flush()
  Start-Sleep -Milliseconds 500
}
