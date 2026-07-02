@echo off
setlocal

rem ============================================================================
rem copy-to-project.bat
rem
rem Copies whichever of stub32.exe / stub64.exe compile-stub.bat actually
rem built into ..\src\native\, where EmbeddedStubs.java's source files live.
rem If one of the two wasn't built (e.g. you only have a 64-bit toolchain
rem configured), its existing copy in ..\src\native\ is simply left alone
rem rather than treated as an error.
rem ============================================================================

set "NOPAUSE=%~1"
set "EXITCODE=0"
set "COPIED_ANY=0"

cd /d "%~dp0"

if not exist "..\src\native" (
    echo ERROR: ..\src\native not found - is this .bat still inside Wraptor\native-stub\?
    set "EXITCODE=1"
    goto :end
)

if exist stub32.exe (
    copy /y stub32.exe "..\src\native\stub32.exe" >nul
    if errorlevel 1 (
        echo COPY FAILED for stub32.exe.
        set "EXITCODE=1"
    ) else (
        echo Copied stub32.exe
        set "COPIED_ANY=1"
    )
) else (
    echo NOTE: stub32.exe not found here - leaving ..\src\native\stub32.exe as-is ^(not overwritten^).
)

if exist stub64.exe (
    copy /y stub64.exe "..\src\native\stub64.exe" >nul
    if errorlevel 1 (
        echo COPY FAILED for stub64.exe.
        set "EXITCODE=1"
    ) else (
        echo Copied stub64.exe
        set "COPIED_ANY=1"
    )
) else (
    echo NOTE: stub64.exe not found here - leaving ..\src\native\stub64.exe as-is ^(not overwritten^).
)

if "%COPIED_ANY%"=="0" (
    echo ERROR: neither stub32.exe nor stub64.exe found here - run compile-stub.bat first.
    set "EXITCODE=1"
)

:end
if /I not "%NOPAUSE%"=="NOPAUSE" (
    echo.
    pause
)
exit /b %EXITCODE%
