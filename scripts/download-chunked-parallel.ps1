param(
    [Parameter(Mandatory=$true)][string]$Url,
    [Parameter(Mandatory=$true)][string]$OutFile,
    [Parameter(Mandatory=$true)][long]$TotalSize,
    [int]$ChunkMB = 8,
    [int]$Workers = 6
)

# 并行分块下载器 —— 用多个 curl 进程同时下载不同区间，绕过单连接限速。
$ErrorActionPreference = 'Continue'
$chunkSize = $ChunkMB * 1MB
$totalChunks = [math]::Ceiling($TotalSize / $chunkSize)
$tempDir = Join-Path $env:TEMP "chunked-par-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
Write-Host "总大小 $([math]::Round($TotalSize/1MB)) MB, $totalChunks 块 x ${ChunkMB}MB, $Workers 并行"

# 每轮启动 Workers 个 curl 后台作业
$completed = New-Object 'System.Collections.Generic.HashSet[int]'
$round = 0
while ($completed.Count -lt $totalChunks) {
    $round++
    $batch = @()
    for ($w = 0; $w -lt $Workers; $w++) {
        $idx = -1
        for ($i = 0; $i -lt $totalChunks; $i++) {
            if (-not $completed.Contains($i) -and -not ($batch -contains $i)) { $idx = $i; break }
        }
        if ($idx -lt 0) { break }
        $batch += $idx
        $start = $idx * $chunkSize
        $end = [math]::Min($start + $chunkSize - 1, $TotalSize - 1)
        $part = Join-Path $tempDir ("part{0:D5}" -f $idx)
        $expected = $end - $start + 1
        if ((Test-Path -LiteralPath $part) -and ((Get-Item -LiteralPath $part).Length -eq $expected)) {
            [void]$completed.Add($idx); continue
        }
        $tmp = "$part.tmp"
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }
        Start-Job -ScriptBlock {
            param($u, $s, $e, $o)
            & curl.exe -sL --max-time 300 -r "$s-$e" -o "$o" $u 2>$null | Out-Null
        } -ArgumentList $Url, $start, $end, $tmp | Out-Null
    }
    # 等待本批所有作业
    Get-Job | Where-Object { $_.State -eq 'Running' } | Wait-Job -Timeout 320 | Out-Null
    foreach ($j in (Get-Job)) {
        $out = Receive-Job $j -ErrorAction SilentlyContinue
        Remove-Job $j -Force
    }
    # 校验本批
    foreach ($idx in $batch) {
        if ($completed.Contains($idx)) { continue }
        $start = $idx * $chunkSize
        $end = [math]::Min($start + $chunkSize - 1, $TotalSize - 1)
        $expected = $end - $start + 1
        $tmp = Join-Path $tempDir ("part{0:D5}.tmp" -f $idx)
        $part = Join-Path $tempDir ("part{0:D5}" -f $idx)
        if ((Test-Path -LiteralPath $tmp) -and ((Get-Item -LiteralPath $tmp).Length -eq $expected)) {
            Move-Item -LiteralPath $tmp -Destination $part -Force
            [void]$completed.Add($idx)
        }
    }
    Write-Host "轮次 $round : $($completed.Count)/$totalChunks ($([math]::Round(100*$completed.Count/$totalChunks))%)"
}

Write-Host "拼接..."
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
