# Catgirl Client "Now playing": prints what Windows says is playing (Spotify, YouTube Music,
# browsers, ...) as one JSON line per second. Uses Windows' own media controls, so no logins.
# Album art is sent once per song, as a 64x64 PNG (base64).
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

function Get-Art($props) {
  try {
    if (-not $props.Thumbnail) { return $null }
    $st = Await ($props.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
    if (-not $st -or $st.Size -le 0) { return $null }
    $reader = [Windows.Storage.Streams.DataReader]::new($st.GetInputStreamAt(0))
    $n = Await ($reader.LoadAsync([uint32]$st.Size)) ([uint32])
    if (-not $n) { return $null }
    $bytes = New-Object byte[] $n
    $reader.ReadBytes($bytes)
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
  } catch { return $null }
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
        $line = @{
          title   = [string]$p.Title
          artist  = [string]$p.Artist
          album   = [string]$p.AlbumTitle
          app     = [string]$s.SourceAppUserModelId
          pos     = [Math]::Round($tl.Position.TotalSeconds, 2)
          dur     = [Math]::Round(($tl.EndTime - $tl.StartTime).TotalSeconds, 2)
          playing = ($pb.PlaybackStatus -eq [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionPlaybackStatus]::Playing)
        }
        $key = "$($p.Title)|$($p.Artist)"
        if ($key -ne $lastKey) {
          # The art can show up a moment after the song changes, so try a few times.
          $art = Get-Art $p
          if ($art) { $line.art = $art; $lastKey = $key; $artTries = 0 }
          elseif (++$artTries -ge 4) { $line.art = ''; $lastKey = $key; $artTries = 0 }
        }
      }
    }
  } catch {}
  [Console]::Out.WriteLine(($line | ConvertTo-Json -Compress))
  [Console]::Out.Flush()
  Start-Sleep -Milliseconds 1000
}
