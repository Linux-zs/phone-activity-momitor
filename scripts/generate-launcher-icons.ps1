param(
    [string]$Source = "assets/branding/app-icon-source.png",
    [int]$CropMargin = 170
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

Add-Type -AssemblyName System.Drawing

$repoRoot = Split-Path -Parent $PSScriptRoot
$sourcePath = if ([System.IO.Path]::IsPathRooted($Source)) {
    $Source
} else {
    Join-Path $repoRoot $Source
}

$sourceBitmap = [System.Drawing.Bitmap]::FromFile($sourcePath)

try {
    if ($sourceBitmap.Width -ne $sourceBitmap.Height) {
        throw "Launcher icon source must be square: $($sourceBitmap.Width)x$($sourceBitmap.Height)"
    }

    $cropSize = $sourceBitmap.Width - (2 * $CropMargin)
    if ($cropSize -le 0) {
        throw "CropMargin $CropMargin is too large for a $($sourceBitmap.Width) px source image."
    }

    $sourceRect = [System.Drawing.Rectangle]::new($CropMargin, $CropMargin, $cropSize, $cropSize)
    $targets = [ordered]@{
        "mipmap-mdpi" = 48
        "mipmap-hdpi" = 72
        "mipmap-xhdpi" = 96
        "mipmap-xxhdpi" = 144
        "mipmap-xxxhdpi" = 192
    }

    $outputs = [ordered]@{
        (Join-Path $repoRoot "assets/branding/app-icon-borderless.png") = $cropSize
    }

    foreach ($target in $targets.GetEnumerator()) {
        $outputs[(Join-Path $repoRoot "app/src/main/res/$($target.Key)/ic_launcher.png")] = $target.Value
    }

    foreach ($output in $outputs.GetEnumerator()) {
        $destination = $output.Key
        $size = $output.Value
        $destinationDirectory = Split-Path -Parent $destination
        [System.IO.Directory]::CreateDirectory($destinationDirectory) | Out-Null

        $bitmap = [System.Drawing.Bitmap]::new(
            $size,
            $size,
            [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
        )

        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
                $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                $graphics.DrawImage(
                    $sourceBitmap,
                    [System.Drawing.Rectangle]::new(0, 0, $size, $size),
                    $sourceRect,
                    [System.Drawing.GraphicsUnit]::Pixel
                )
            } finally {
                $graphics.Dispose()
            }

            $bitmap.Save($destination, [System.Drawing.Imaging.ImageFormat]::Png)
            Write-Host "Generated $destination ($size x $size)"
        } finally {
            $bitmap.Dispose()
        }
    }
} finally {
    $sourceBitmap.Dispose()
}
