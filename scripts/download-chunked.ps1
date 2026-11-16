param(
    [Parameter(Mandatory=$true)][string]$Url,
    [Parameter(Mandatory=$true)][string]$OutFile,
    [Parameter(Mandatory=$true)][long]$TotalSize,
    [int]$ChunkMB = 8
)

# 分块下载器 —— 应对 schannel 大文件下载中断。
# 每块独立 curl 请求，失败重试；最后按序拼接。
$ErrorActionPreference = 'Stop'
$chunkSize = $ChunkMB * 1MB
$tempDir = Join-Path $env:TEMP "chunked-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

$totalChunks = [math]::Ceiling($TotalSize / $chunkSize)
Write-Host "总大小 $([math]::Round($TotalSize/1MB)) MB, 分 $totalChunks 块, 每块 $ChunkMB MB"

# 从已有文件恢复：计算已完成的块
$existing = @()
if (Test-Path $OutFile) {
    $existingLen = (Get-Item $OutFile).Length
    $fullChunks = [math]::Floor($existingLen / $chunkSize)
    for ($i = 0; $i -lt $fullChunks; $i++) {
        $part = Join-Path $tempDir ("part{0:D5}" -f $i)
        Copy-Item -Force $OutFile $part
        # 截断到正确大小
        $fs = [IO.File]::OpenWrite($part); $fs.SetLength($chunkSize); $fs.Close()
        $existing += $i
    }
    Write-Host "恢复: 已有 $fullChunks 个完整块"
}

for ($i = 0; $i -lt $totalChunks; $i++) {
    if ($existing -contains $i) { continue }
    $start = $i * $chunkSize
    $end = [math]::Min($start + $chunkSize - 1, $TotalSize - 1)
    $part = Join-Path $tempDir ("part{0:D5}" -f $i)
    $expectedLen = $end - $start + 1
    $ok = $false
    $partAbs = [IO.Path]::GetFullPath($part)
    for ($attempt = 1; $attempt -le 15 -and -not $ok; $attempt++) {
        # 每次写到临时文件再移动，避免部分写入被误判为完成
        $tmpPart = "$partAbs.tmp"
        if (Test-Path -LiteralPath $tmpPart) { Remove-Item -LiteralPath $tmpPart -Force }
        & curl.exe -sL --max-time 240 -r "$start-$end" -o "$tmpPart" $Url 2>$null | Out-Null
        $len = if (Test-Path -LiteralPath $tmpPart) { (Get-Item -LiteralPath $tmpPart).Length } else { 0 }
        if ($len -eq $expectedLen) {
            Move-Item -LiteralPath $tmpPart -Destination $partAbs -Force
            $ok = $true
        } else {
            Write-Host "  块 $i 第 $attempt 次失败 (size=$len/$expectedLen)，重试..."
            Start-Sleep -Seconds 2
        }
    }
    if (-not $ok) { throw "块 $i 下载失败" }
    if ($i % 10 -eq 0) { Write-Host "  进度: $i/$totalChunks ($([math]::Round(100*$i/$totalChunks))%)" }
}

# 拼接
Write-Host "拼接中..."
$out = [IO.File]::Create($OutFile)
try {
    for ($i = 0; $i -lt $totalChunks; $i++) {
        $part = Join-Path $tempDir ("part{0:D5}" -f $i)
        $bytes = [IO.File]::ReadAllBytes($part)
        $out.Write($bytes, 0, $bytes.Length)
    }
} finally { $out.Close() }
Remove-Item -Recurse -Force $tempDir
Write-Host "完成: $([math]::Round((Get-Item $OutFile).Length/1MB)) MB"
