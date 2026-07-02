@echo off
setlocal

rem ============================================================================
rem build-all.bat
rem
rem Runs the full native-stub rebuild in one shot:
rem   1. compile-stub.bat            -> stub32.exe / stub64.exe
rem   2. copy-to-project.bat         -> ..\src\native\
rem   3. generate-embedded-stubs.bat -> ..\src\wraptor\resource\EmbeddedStubs.java
rem
rem The three steps run with their own pause-at-the-end suppressed (they're
rem passed NOPAUSE); this script pauses once at the very end instead, so you
rem still get a chance to read the output before the window closes, whether
rem this finished successfully or one of the steps failed partway through.
rem
rem compile-stub.bat returns a nonzero exit code both when NOTHING built
rem (e.g. MSYS2 missing entirely) and when only ONE of the two
rem architectures built (e.g. the 32-bit toolchain has a problem but x64 is
rem fine). Those aren't the same situation: copy-to-project.bat already
rem handles "only one exists" gracefully by leaving the other one's existing
rem copy alone, so hard-aborting the whole pipeline on any nonzero exit code
rem here would throw away a perfectly good partial build. Instead, only
rem abort if NEITHER stub exists after compile-stub.bat runs - if at least
rem one does, warn and continue so you still get an updated EmbeddedStubs.java
rem for the architecture(s) that did build.
rem
rem After this finishes, do a Clean and Build on the Wraptor project in
rem NetBeans to pick up the regenerated EmbeddedStubs.java.
rem ============================================================================

cd /d "%~dp0"

call compile-stub.bat NOPAUSE
if errorlevel 1 (
    if not exist stub32.exe if not exist stub64.exe (
        echo.
        echo Neither stub32.exe nor stub64.exe was produced - nothing to continue with.
        goto :fail
    )
    echo.
    echo WARNING: compile-stub.bat reported a problem with one architecture, but at
    echo least one stub exe was produced - continuing with what's available.
)

call copy-to-project.bat NOPAUSE
if errorlevel 1 goto :fail

call generate-embedded-stubs.bat NOPAUSE
if errorlevel 1 goto :fail

echo.
echo ================================================================
echo All done: stub32.exe / stub64.exe rebuilt, copied into src\native\,
echo and EmbeddedStubs.java regenerated.
echo Now do a Clean and Build on the Wraptor project in NetBeans.
echo ================================================================
echo.
pause
exit /b 0

:fail
echo.
echo ================================================================
echo BUILD-ALL FAILED - see the output above for which step failed.
echo ================================================================
echo.
pause
exit /b 1