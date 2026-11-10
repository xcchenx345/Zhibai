# mm_encode.ps1 — owner-only encoder for the mm- key wrap (mm.transmute.v1)
#
# Converts a real API key (sk-... / tp-...) into an mm-... blob that the app
# unwraps natively at runtime. Give the mm- string to testers instead of the
# real key — the APK contains no encoder, and the unwrap core (mm_codec.cpp)
# only accepts blobs whose HMAC verifies and whose plaintext looks like a key.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\mm_encode.ps1 sk-xxxxx
#   .\tools\mm_encode.ps1 tp-xxxxx
#   .\tools\mm_encode.ps1 sk-xxxxx -NoPause   (automation: no clipboard wait)
#
# The result is printed AND copied to the clipboard; the window then waits for
# Enter so it doesn't vanish before you can copy it (right-click → Run with
# PowerShell closes instantly otherwise).
#
# Protocol (must stay byte-identical with app/src/main/cpp/mm_codec.cpp):
#   blob = 0x01 || (utf8(key) XOR keystream) || HMAC-SHA256(mackey, ct)[0..8]
#   seed   = SHA256("mm.transmute.v1|seed|ZhiBai.keywrap.2026")
#   mackey = SHA256(seed || "mm.transmute.v1|mac|ZhiBai.keywrap.2026")
#   keystream block i = SHA256(seed || BE32(i))
#   body   = custom base64 (REVERSED standard alphabet, '=' padding)
#   output = "mm-" + body

param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Key,
    [switch]$NoPause
)

$ErrorActionPreference = "Stop"
$Key = $Key.Trim()
if ($Key -notmatch '^(sk-|tp-)[A-Za-z0-9._\-]+$') {
    Write-Error "Key must look like sk-... or tp-... (got: $($Key.Substring(0, [Math]::Min(6, $Key.Length)))...)"
    exit 1
}

$Alphabet = '+/9876543210zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA'
$SeedSalt = [System.Text.Encoding]::UTF8.GetBytes('mm.transmute.v1|seed|ZhiBai.keywrap.2026')
$MacSalt  = [System.Text.Encoding]::UTF8.GetBytes('mm.transmute.v1|mac|ZhiBai.keywrap.2026')

function Get-Sha256([byte[]]$Data) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return $sha.ComputeHash($Data) } finally { $sha.Dispose() }
}

$seed = Get-Sha256 $SeedSalt
$mackey = Get-Sha256 ($seed + $MacSalt)

$plain = [System.Text.Encoding]::UTF8.GetBytes($Key)

# keystream: SHA256(seed || BE32(i)) blocks until long enough
$ks = New-Object 'System.Collections.Generic.List[byte]'
$idx = 0
while ($ks.Count -lt $plain.Length) {
    $be = [byte[]]@(
        (($idx -shr 24) -band 0xFF),
        (($idx -shr 16) -band 0xFF),
        (($idx -shr 8) -band 0xFF),
        ($idx -band 0xFF)
    )
    foreach ($b in (Get-Sha256 ($seed + $be))) { $ks.Add($b) }
    $idx++
}

$ct = New-Object byte[] $plain.Length
for ($i = 0; $i -lt $plain.Length; $i++) {
    $ct[$i] = $plain[$i] -bxor $ks[$i]
}

$hmac = [System.Security.Cryptography.HMACSHA256]::new($mackey)
try { $macFull = $hmac.ComputeHash($ct) } finally { $hmac.Dispose() }
$mac = [byte[]]$macFull[0..7]

$blob = New-Object byte[] (1 + $ct.Length + 8)
$blob[0] = 0x01
[Array]::Copy($ct, 0, $blob, 1, $ct.Length)
[Array]::Copy($mac, 0, $blob, 1 + $ct.Length, 8)

# custom base64 encode (alphabet above, standard grouping + '=' padding)
$sb = New-Object System.Text.StringBuilder
for ($i = 0; $i -lt $blob.Length; $i += 3) {
    $b0 = $blob[$i]
    $have1 = ($i + 1 -lt $blob.Length)
    $have2 = ($i + 2 -lt $blob.Length)
    $b1 = if ($have1) { $blob[$i + 1] } else { 0 }
    $b2 = if ($have2) { $blob[$i + 2] } else { 0 }
    [void]$sb.Append($Alphabet[($b0 -shr 2)])
    [void]$sb.Append($Alphabet[((($b0 -band 0x03) -shl 4) -bor ($b1 -shr 4))])
    if ($have2) {
        [void]$sb.Append($Alphabet[((($b1 -band 0x0F) -shl 2) -bor ($b2 -shr 6))])
        [void]$sb.Append($Alphabet[($b2 -band 0x3F)])
    } elseif ($have1) {
        [void]$sb.Append($Alphabet[(($b1 -band 0x0F) -shl 2)])
        [void]$sb.Append('=')
    } else {
        [void]$sb.Append('=')
        [void]$sb.Append('=')
    }
}

$mm = 'mm-' + $sb.ToString()
Write-Output $mm

# Also park it in the clipboard — the window used to close the moment the
# result printed, racing your Ctrl+V.
$copied = $false
try { Set-Clipboard -Value $mm; $copied = $true } catch { }

if (-not $NoPause) {
    try {
        Write-Host ""
        if ($copied) {
            Write-Host "OK - result copied to clipboard, paste it directly (Ctrl+V)."
        } else {
            Write-Host "Clipboard unavailable - copy the mm- line above manually."
        }
        Write-Host "Press Enter to close..."
        [void][Console]::ReadLine()
    } catch { }
}
