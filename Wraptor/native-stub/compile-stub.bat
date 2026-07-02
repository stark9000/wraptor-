@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

set "NOPAUSE=%~1"
set "LOG=build.log"
set "EXITCODE=0"
set "BUILT32=0"
set "BUILT64=0"

echo ================================================== > "%LOG%"
echo Build started %DATE% %TIME%>> "%LOG%"
echo ==================================================>> "%LOG%"

echo.
echo ==============================
echo   STUB BUILD SYSTEM (MSYS2)
echo ==============================
echo.

rem ==================================================
rem Detect MSYS2 installation
rem ==================================================
set "MSYS2_ROOT="

if exist "E:\msys64\mingw64\bin\gcc.exe" set "MSYS2_ROOT=E:\msys64"
if exist "C:\msys64\mingw64\bin\gcc.exe" set "MSYS2_ROOT=C:\msys64"

if not defined MSYS2_ROOT (
    echo ERROR: MSYS2 not found.
    echo ERROR: MSYS2 not found.>> "%LOG%"
    set "EXITCODE=1"
    goto :summary
)

echo MSYS2 detected at: %MSYS2_ROOT%
echo MSYS2 detected at: %MSYS2_ROOT%>> "%LOG%"

set "BIN64=%MSYS2_ROOT%\mingw64\bin"
set "BIN32=%MSYS2_ROOT%\mingw32\bin"

set "GCC64=%BIN64%\x86_64-w64-mingw32-gcc.exe"
set "GCC32=%BIN32%\i686-w64-mingw32-gcc.exe"
set "WINDRES64=%BIN64%\x86_64-w64-mingw32-windres.exe"
set "WINDRES32=%BIN32%\i686-w64-mingw32-windres.exe"

rem fallback if the target-prefixed wrapper names aren't present - use the
rem plain names from the SAME arch-specific bin dir (never a bare "gcc"/
rem "windres" resolved from ambient PATH, since that can silently pick the
rem wrong architecture's tool)
if not exist "%GCC64%" set "GCC64=%BIN64%\gcc.exe"
if not exist "%GCC32%" set "GCC32=%BIN32%\gcc.exe"
if not exist "%WINDRES64%" set "WINDRES64=%BIN64%\windres.exe"
if not exist "%WINDRES32%" set "WINDRES32=%BIN32%\windres.exe"

rem gcc's own frontend benefits from Windows' "check the launched exe's own
rem folder first" DLL search rule, but the helper processes it spawns
rem internally (cc1.exe, as.exe, collect2.exe/ld.exe) do NOT live in that
rem same folder and need BIN64/BIN32 on PATH to find their sibling runtime
rem DLLs (libgcc_s_seh-1.dll for x64, libgcc_s_dw2-1.dll for x86, etc.) -
rem that missing PATH entry is what "libgcc_s_dw2-1.dll was not found"
rem actually means. Both dirs stay on PATH for the whole script; every tool
rem below is still invoked by its full path, so there's no ambiguity about
rem which architecture actually runs.
set "PATH=%BIN64%;%BIN32%;%PATH%"

echo.
echo === Compiler Detection ===
echo.

rem ==================================================
rem Validate 64-bit compiler
rem ==================================================
if exist "%GCC64%" (
    for /f "delims=" %%M in ('"%GCC64%" -dumpmachine') do set "TRIPLE64=%%M"
    echo [OK] x64 GCC: %GCC64%
    echo       Target: !TRIPLE64!
    echo [OK] x64 GCC: %GCC64%>> "%LOG%"
) else (
    echo [WARN] 64-bit compiler not found
    echo [WARN] 64-bit compiler not found>> "%LOG%"
)

rem ==================================================
rem Validate 32-bit compiler
rem ==================================================
if exist "%GCC32%" (
    for /f "delims=" %%M in ('"%GCC32%" -dumpmachine') do set "TRIPLE32=%%M"
    echo [OK] x86 GCC: %GCC32%
    echo       Target: !TRIPLE32!
    echo [OK] x86 GCC: %GCC32%>> "%LOG%"
) else (
    echo [WARN] 32-bit compiler not found
    echo [WARN] 32-bit compiler not found>> "%LOG%"
)

echo.
echo ==============================
echo   BUILDING
echo ==============================
echo.

rem ==================================================
rem Build 64-bit
rem ==================================================
if exist "%GCC64%" (
    echo [x64] Compiling resources...
    "%WINDRES64%" resource.rc -O coff -o resource64.o >> "%LOG%" 2>&1
    if errorlevel 1 (
        echo [FAIL] x64 resource compile failed - see %LOG%
        echo [FAIL] x64 resource compile failed>> "%LOG%"
        set "EXITCODE=1"
    ) else (
        echo [x64] Building stub64.exe...
        "%GCC64%" -O2 -Wall -mwindows stub.c resource64.o -o stub64.exe -lshlwapi -ladvapi32 -static-libgcc -static-libstdc++ >> "%LOG%" 2>&1
        if errorlevel 1 (
            echo [FAIL] x64 build failed - see %LOG%
            echo [FAIL] x64 build failed>> "%LOG%"
            set "EXITCODE=1"
        ) else (
            set "BUILT64=1"
            echo [OK] stub64.exe built
        )
    )
)

rem ==================================================
rem Build 32-bit
rem ==================================================
if exist "%GCC32%" (
    echo [x86] Compiling resources...
    "%WINDRES32%" resource.rc -O coff -o resource32.o >> "%LOG%" 2>&1
    if errorlevel 1 (
        echo [FAIL] x86 resource compile failed - see %LOG%
        echo [FAIL] x86 resource compile failed>> "%LOG%"
        set "EXITCODE=1"
    ) else (
        echo [x86] Building stub32.exe...
        "%GCC32%" -O2 -Wall -mwindows stub.c resource32.o -o stub32.exe -lshlwapi -ladvapi32 -static-libgcc -static-libstdc++ >> "%LOG%" 2>&1
        if errorlevel 1 (
            echo [FAIL] x86 build failed - see %LOG%
            echo [FAIL] x86 build failed>> "%LOG%"
            set "EXITCODE=1"
        ) else (
            set "BUILT32=1"
            echo [OK] stub32.exe built
        )
    )
)

:summary
echo.
echo ==============================
echo        SUMMARY
echo ==============================
echo.

if "%BUILT64%"=="1" (
    echo stub64.exe : BUILT
    echo stub64.exe : BUILT>> "%LOG%"
) else (
    echo stub64.exe : NOT BUILT
    echo stub64.exe : NOT BUILT>> "%LOG%"
)

if "%BUILT32%"=="1" (
    echo stub32.exe : BUILT
    echo stub32.exe : BUILT>> "%LOG%"
) else (
    echo stub32.exe : NOT BUILT
    echo stub32.exe : NOT BUILT>> "%LOG%"
)

if "%EXITCODE%"=="1" (
    echo.
    echo Some step failed - see %LOG% for the full compiler output.
)

echo.
echo Log saved to: %LOG%
echo.

echo Build finished %DATE% %TIME%>> "%LOG%"
echo ==================================================>> "%LOG%"

if /I not "%NOPAUSE%"=="NOPAUSE" (
    pause
)
exit /b %EXITCODE%