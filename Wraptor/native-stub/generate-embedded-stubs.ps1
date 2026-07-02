<#
.SYNOPSIS
  Regenerates src\wraptor\resource\EmbeddedStubs.java from stub32.exe / stub64.exe.

.DESCRIPTION
  Reads both stub exes, base64-encodes them with .NET's Convert class (the
  same RFC4648 alphabet Java's Base64.getDecoder() expects - no line
  wrapping, no surprises), and writes each as a Java string array split into
  ~57600-char chunks.

  The chunk size is a deliberate compromise between two DIFFERENT hard
  limits:
    - A single Java string constant's UTF-8 encoding cannot exceed 65535
      bytes (a class-file format limit) - so each chunk must stay under that.
    - STUB32_CHUNKS and STUB64_CHUNKS are static fields, so their array
      initializer bytecode all lands in one <clinit> method, which has its
      own hard 64KB bytecode ceiling. Many small array elements (e.g. one
      per ~64-char base64 line, as a naive line-by-line approach would
      produce) means thousands of "dup/ldc/iconst/aastore" instructions -
      comfortably enough to blow that ceiling on its own ("code too large"),
      even though no individual string was anywhere near 65535 bytes.
  57600 chars/chunk keeps well clear of the first limit while keeping the
  element count down to a handful per array, keeping <clinit> tiny.
#>
param(
    [Parameter(Mandatory = $true)][string]$Stub32,
    [Parameter(Mandatory = $true)][string]$Stub64,
    [Parameter(Mandatory = $true)][string]$Template,
    [Parameter(Mandatory = $true)][string]$OutFile
)

$ErrorActionPreference = "Stop"
$ChunkSize = 57600

function Write-ChunkedArray {
    param(
        [System.IO.StreamWriter]$Writer,
        [string]$ArrayName,
        [string]$ExePath
    )
    $bytes = [System.IO.File]::ReadAllBytes($ExePath)
    $b64 = [System.Convert]::ToBase64String($bytes)
    $total = $b64.Length
    $chunkCount = [Math]::Ceiling($total / [double]$ChunkSize)
    Write-Host ("    {0}: {1} bytes -> {2} base64 chars -> {3} chunk(s)" -f $ArrayName, $bytes.Length, $total, $chunkCount)

    $Writer.WriteLine("    private static final String[] $ArrayName = {")
    $i = 0
    while ($i -lt $total) {
        $len = [Math]::Min($ChunkSize, $total - $i)
        $chunk = $b64.Substring($i, $len)
        $Writer.WriteLine('        "' + $chunk + '",')
        $i += $len
        Write-Host "." -NoNewline
    }
    Write-Host ""
    $Writer.WriteLine("    };")
}

if (-not (Test-Path -LiteralPath $Stub32)) { Write-Error "Not found: $Stub32"; exit 1 }
if (-not (Test-Path -LiteralPath $Stub64)) { Write-Error "Not found: $Stub64"; exit 1 }
if (-not (Test-Path -LiteralPath $Template)) { Write-Error "Not found: $Template"; exit 1 }

Copy-Item -LiteralPath $Template -Destination $OutFile -Force

$writer = New-Object System.IO.StreamWriter($OutFile, $true)
try {
    Write-ChunkedArray -Writer $writer -ArrayName "STUB32_CHUNKS" -ExePath $Stub32
    $writer.WriteLine("")
    Write-ChunkedArray -Writer $writer -ArrayName "STUB64_CHUNKS" -ExePath $Stub64
    $writer.WriteLine("}")
}
finally {
    $writer.Close()
}


 