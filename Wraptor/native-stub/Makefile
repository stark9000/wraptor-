# Cross-compiles the native launcher stub for Windows x86 and x64 using
# MinGW-w64. Run this from Linux/macOS/WSL — output is stub32.exe /
# stub64.exe, which get bundled as resources inside the builder-gui jar.
#
# Install prerequisites (Debian/Ubuntu):
#   sudo apt-get install mingw-w64
#
# Usage:
#   make            # builds both
#   make stub32.exe
#   make stub64.exe
#   make clean

CC32 = i686-w64-mingw32-gcc
CC64 = x86_64-w64-mingw32-gcc
WINDRES32 = i686-w64-mingw32-windres
WINDRES64 = x86_64-w64-mingw32-windres

CFLAGS = -O2 -Wall -mwindows
LDLIBS = -lshlwapi -ladvapi32

all: stub32.exe stub64.exe

resource32.o: resource.rc app.manifest default.ico placeholder_config.bin placeholder_jars.bin
	$(WINDRES32) resource.rc -O coff -o resource32.o

resource64.o: resource.rc app.manifest default.ico placeholder_config.bin placeholder_jars.bin
	$(WINDRES64) resource.rc -O coff -o resource64.o

stub32.exe: stub.c resource32.o
	$(CC32) $(CFLAGS) stub.c resource32.o -o stub32.exe $(LDLIBS)

stub64.exe: stub.c resource64.o
	$(CC64) $(CFLAGS) stub.c resource64.o -o stub64.exe $(LDLIBS)

clean:
	rm -f stub32.exe stub64.exe resource32.o resource64.o

.PHONY: all clean
