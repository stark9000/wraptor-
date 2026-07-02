Rebuilding the native launcher stub
====================================

The Java side (Wraptor itself) never compiles C code - stub32.exe/stub64.exe
are baked into wraptor.resource.EmbeddedStubs as base64, generated ahead of
time from the source in this folder. You only need to touch this folder when
stub.c, resource.rc, or app.manifest actually change.

Requirements
------------
- MinGW-w64 for 64-bit builds: compile-stub.bat uses whatever plain "gcc" is
  already on your PATH, as long as it targets x86_64 (checked automatically
  via `gcc -dumpmachine`). If you can already run `gcc` from any cmd prompt,
  you're set - no configuration needed.
- MinGW-w64 for 32-bit builds (optional): Windows-native MinGW-w64 installs
  are usually one folder per architecture, each with its own plain
  gcc.exe/windres.exe (this is different from Linux's mingw-w64 package,
  which uses triplet-prefixed names like x86_64-w64-mingw32-gcc for both
  architectures from a shared PATH). If you want stub32.exe too, install a
  32-bit MinGW-w64 toolchain and edit the MINGW32_DIR line near the top of
  compile-stub.bat to point at its bin folder. Leave it unconfigured and
  32-bit is simply skipped - stub64.exe still builds fine, and the project's
  existing src\native\stub32.exe (if any) is left untouched.
- certutil.exe, already built into every Windows install (used for base64,
  no extra download needed)

Usage
-----
Easiest: double-click build-all.bat. It runs all three steps below in order.

Or run them one at a time, in this order:

  1. compile-stub.bat
     Cross-compiles stub.c -> stub32.exe / stub64.exe, right here in
     native-stub\.

  2. copy-to-project.bat
     Copies those two exe files into ..\src\native\, where EmbeddedStubs.java
     expects to find them.

  3. generate-embedded-stubs.bat
     Regenerates ..\src\wraptor\resource\EmbeddedStubs.java from
     ..\src\native\stub32.exe / stub64.exe (base64, via certutil - no Java or
     Python needed on your machine for this step).

Then, in NetBeans: right-click the Wraptor project -> Clean and Build, so the
regenerated EmbeddedStubs.java actually gets recompiled.

Files here
----------
  stub.c                          the launcher's C source
  resource.rc                     RC script: CONFIG/JARS placeholders,
                                   VERSIONINFO block, icon, manifest
  app.manifest                    default manifest (asInvoker); Wraptor swaps
                                   this to requireAdministrator at build time
                                   when "Request administrator privileges" is
                                   checked
  default.ico                     placeholder icon compiled into the stub;
                                   Wraptor replaces this at build time too
  placeholder_config.bin/
  placeholder_jars.bin            empty placeholders for the CONFIG/JARS
                                   RCDATA resources stub.c reads at runtime -
                                   Wraptor overwrites both at build time
  Makefile                        equivalent build for Linux/macOS/WSL (uses
                                   `make`), kept for non-Windows dev machines
  EmbeddedStubs.header.template   the fixed header/methods portion of
                                   EmbeddedStubs.java; generate-embedded-
                                   stubs.bat appends the two chunk arrays
                                   after this and writes the result out
  compile-stub.bat
  copy-to-project.bat
  generate-embedded-stubs.bat
  build-all.bat                   the four .bat files described above
