@echo off
setlocal enabledelayedexpansion

rem ============================================================================
rem generate-embedded-stubs.bat
rem
rem Regenerates src\wraptor\resource\EmbeddedStubs.java from
rem src\native\stub32.exe / stub64.exe.
rem
rem Run compile-stub.bat and copy-to-project.bat first, so src\native has the
rem exe files you actually want embedded.
rem
rem The actual base64-encoding and chunking work is done by
rem generate-embedded-stubs.ps1 (PowerShell, preinstalled on every supported
rem version of Windows). This .bat is just a thin wrapper around it.
rem
rem Why not do the chunking directly in batch, the way this script used to:
rem certutil -encode wraps its output at ~64 characters per line, and this
rem script previously turned each of those lines into its own Java string
rem literal - which produces 5,000-6,000+ array elements for a single stub
rem and overflows the JVM's 65,535-byte-per-method <clinit> bytecode limit
rem ("code too large"), even though no individual string was anywhere near
rem its own 65,535-byte constant-pool limit.
rem
rem The fix is to group many base64 characters (~57,600) into each string
rem literal instead of one per certutil line. Building that up character-
rem group-by-character-group in batch means writing a literal double-quote
rem character mid-stream from inside a delayed-expansion FOR loop - fragile
rem in practice (quoting/escaping across parenthesized blocks + delayed
rem expansion has a lot of sharp edges, and an earlier version of this
rem script that tried it produced a single ~366,000-character blob with no
rem chunk boundaries at all instead of ~7 clean chunks). PowerShell's
rem [System.Convert]::ToBase64String + string.Substring does the same
rem chunking directly and reliably, with no such issues, so that's what
rem generate-embedded-stubs.ps1 uses.
rem ============================================================================

set "NOPAUSE=%~1"
set "EXITCODE=0"

cd /d "%~dp0"

set "STUB32=..\src\native\stub32.exe"
set "STUB64=..\src\native\stub64.exe"
set "OUT=..\src\wraptor\resource\EmbeddedStubs.java"
set "TEMPLATE=EmbeddedStubs.header.template"
set "PSSCRIPT=%~dp0generate-embedded-stubs.ps1"

if not exist "%STUB32%" (
    echo ERROR: %STUB32% not found - run compile-stub.bat and copy-to-project.bat first.
    set "EXITCODE=1"
    goto :end
)
if not exist "%STUB64%" (
    echo ERROR: %STUB64% not found - run compile-stub.bat and copy-to-project.bat first.
    set "EXITCODE=1"
    goto :end
)
if not exist "%TEMPLATE%" (
    echo ERROR: %TEMPLATE% not found next to this .bat file.
    set "EXITCODE=1"
    goto :end
)
if not exist "%PSSCRIPT%" (
    echo ERROR: %PSSCRIPT% not found next to this .bat file.
    set "EXITCODE=1"
    goto :end
)

echo === Generating %OUT% via generate-embedded-stubs.ps1 ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%PSSCRIPT%" ^
    -Stub32 "%STUB32%" ^
    -Stub64 "%STUB64%" ^
    -Template "%TEMPLATE%" ^
    -OutFile "%OUT%"
if errorlevel 1 (
    echo FAILED to generate %OUT% - see PowerShell output above.
    set "EXITCODE=1"
    goto :end
)

echo.
echo Done. Regenerated %OUT%

:end
if /I not "%NOPAUSE%"=="NOPAUSE" (
    echo.
    pause
)
exit /b %EXITCODE%