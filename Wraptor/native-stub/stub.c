/*
 * JLauncher native stub.
 * Compiled ONCE per architecture with MinGW (see Makefile) into
 * stub32.exe / stub64.exe. These are embedded, unmodified, inside the
 * builder-gui's own jar and copied+patched per project — this file is
 * never recompiled per build.
 *
 * Responsibilities:
 *   1. Read CONFIG + JARS resources embedded (by the Java-side
 *      ResourcePatcher) into THIS running exe.
 *   2. Extract lib/main jars to %TEMP%\<appname>_<hash>\
 *   3. Locate a suitable JRE (registry / JAVA_HOME / PATH).
 *   4. Spawn javaw.exe -cp <classpath> <mainClass> <passthrough args>
 *   5. Forward the child's exit code.
 *
 * Build: see Makefile (i686-w64-mingw32-gcc / x86_64-w64-mingw32-gcc)
 */

#include <windows.h>
#include <shlwapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <ctype.h>

#ifdef _MSC_VER
#pragma comment(lib, "shlwapi.lib")
#endif
/* MinGW gets shlwapi linked via -lshlwapi on the compiler command line
   instead (see Makefile / compile-stub.bat) - this pragma is MSVC-only
   and gcc just warns "ignoring" it if it's not guarded like this. */

#define MAX_CFG_LINES 64
#define MAX_LINE      1024
#define CAPTURE_CAP   8000   /* max bytes of child stdout/stderr kept for the crash dialog */

typedef struct {
    HANDLE hRead;
    char  *buf;       /* CAPTURE_CAP + 1 bytes, caller-owned */
    DWORD  used;       /* bytes filled so far, <= CAPTURE_CAP */
} DrainCtx;

typedef struct {
    char appName[256];
    char mainClass[256];
    char jvmArgs[1024];
    char jreMin[16];
    char jreMax[16];
    int  singleInstance;
    int  requestAdmin;      /* informational only here; real elevation is
                                driven by the embedded manifest resource */
    char mainJarName[260];
} AppConfig;

/* ---------- utility ---------- */

static void fatal(const char *msg) {
    MessageBoxA(NULL, msg, "Launch error", MB_ICONERROR | MB_OK);
    ExitProcess(1);
}

/*
 * snprintf wrapper used for every path/string we build. Two things it does
 * differently from a bare snprintf call:
 *
 *  1. Truncation is fatal, not silent. A silently-truncated path (e.g. the
 *     JVM launch command) wouldn't fail cleanly here - it'd fail confusingly
 *     later, or worse, point at the wrong file. Since all inputs going into
 *     these calls (app name, jar names, install paths) are realistically
 *     short, hitting this in practice would mean something is already very
 *     wrong, and that's worth a clear message instead of a mysterious
 *     downstream failure.
 *
 *  2. It also happens to eliminate gcc's -Wformat-truncation false
 *     positives on these calls: that warning fires when gcc can see, from a
 *     fixed-size destination ARRAY at the call site (plus -O2 inlining
 *     across this whole file), that some theoretical worst-case input could
 *     overflow a fixed MAX_PATH-sized buffer - even though in practice
 *     these strings never get remotely that long. Routing every call
 *     through a function taking a plain `char *` + runtime `cap` (rather
 *     than snprintf directly on a `char buf[N]`) removes the fixed-array
 *     type information gcc's analysis needs to make that (here, overly
 *     conservative) call, while the truncation check above keeps the actual
 *     runtime behavior just as safe - arguably safer, since it now fails
 *     loudly instead of silently.
 */
static void xsnprintf(char *buf, size_t cap, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, cap, fmt, ap);
    va_end(ap);
    if (n < 0 || (size_t)n >= cap) {
        fatal("Internal error: a path or string was too long to fit (build/config problem).");
    }
}

/* very small djb2 hash, good enough to fingerprint the embedded JARS blob
   so we can skip re-extracting on every launch */
static unsigned long hash_bytes(const unsigned char *data, size_t len) {
    unsigned long h = 5381;
    for (size_t i = 0; i < len; i++) h = ((h << 5) + h) + data[i];
    return h;
}

/* ---------- resource access ---------- */

static BOOL load_resource(const char *name, unsigned char **outData, DWORD *outSize) {
    HMODULE self = GetModuleHandleA(NULL);
    HRSRC hRes = FindResourceA(self, name, RT_RCDATA);
    if (!hRes) return FALSE;
    HGLOBAL hData = LoadResource(self, hRes);
    if (!hData) return FALSE;
    *outData = (unsigned char *)LockResource(hData);
    *outSize = SizeofResource(self, hRes);
    return (*outData != NULL && *outSize > 0);
}

/* ---------- CONFIG parsing: "key=value\n" utf8 blob ---------- */

static void parse_config(const unsigned char *data, DWORD size, AppConfig *cfg) {
    memset(cfg, 0, sizeof(AppConfig));
    strcpy(cfg->jreMin, "");
    strcpy(cfg->jreMax, "");

    char *buf = (char *)malloc(size + 1);
    memcpy(buf, data, size);
    buf[size] = '\0';

    char *line = strtok(buf, "\r\n");
    while (line) {
        char *eq = strchr(line, '=');
        if (eq) {
            *eq = '\0';
            const char *key = line;
            const char *val = eq + 1;
            if (!strcmp(key, "appName"))        strncpy(cfg->appName, val, sizeof(cfg->appName) - 1);
            else if (!strcmp(key, "mainClass"))  strncpy(cfg->mainClass, val, sizeof(cfg->mainClass) - 1);
            else if (!strcmp(key, "jvmArgs"))    strncpy(cfg->jvmArgs, val, sizeof(cfg->jvmArgs) - 1);
            else if (!strcmp(key, "jreMin"))     strncpy(cfg->jreMin, val, sizeof(cfg->jreMin) - 1);
            else if (!strcmp(key, "jreMax"))     strncpy(cfg->jreMax, val, sizeof(cfg->jreMax) - 1);
            else if (!strcmp(key, "singleInstance")) cfg->singleInstance = atoi(val);
            else if (!strcmp(key, "requestAdmin"))   cfg->requestAdmin = atoi(val);
            else if (!strcmp(key, "mainJarName"))    strncpy(cfg->mainJarName, val, sizeof(cfg->mainJarName) - 1);
        }
        line = strtok(NULL, "\r\n");
    }
    free(buf);
}

/* ---------- JARS TOC extraction ----------
   uint32 fileCount
   repeat: uint32 nameLen, utf8 name, uint64 dataLen, bytes data
*/

static void extract_jars(const unsigned char *data, DWORD size, const char *destDir, char *classpathOut, size_t classpathCap) {
    const unsigned char *p = data;
    const unsigned char *end = data + size;

    if (p + 4 > end) fatal("Corrupt JARS resource (truncated header)");
    unsigned int count;
    memcpy(&count, p, 4); p += 4;

    classpathOut[0] = '\0';

    for (unsigned int i = 0; i < count; i++) {
        if (p + 4 > end) fatal("Corrupt JARS resource (name len)");
        unsigned int nameLen;
        memcpy(&nameLen, p, 4); p += 4;

        if (p + nameLen > end) fatal("Corrupt JARS resource (name)");
        char name[MAX_PATH];
        size_t copyLen = nameLen < sizeof(name) - 1 ? nameLen : sizeof(name) - 1;
        memcpy(name, p, copyLen);
        name[copyLen] = '\0';
        p += nameLen;

        if (p + 8 > end) fatal("Corrupt JARS resource (data len)");
        unsigned __int64 dataLen;
        memcpy(&dataLen, p, 8); p += 8;

        if (p + dataLen > end) fatal("Corrupt JARS resource (data)");

        char outPath[MAX_PATH];
        xsnprintf(outPath, sizeof(outPath), "%s\\%s", destDir, name);

        /* skip re-write if file already exists with matching size (cheap
           fast-path for repeated launches; a real impl would hash-check) */
        WIN32_FILE_ATTRIBUTE_DATA fad;
        BOOL exists = GetFileAttributesExA(outPath, GetFileExInfoStandard, &fad);
        BOOL sameSize = exists &&
            (((unsigned __int64)fad.nFileSizeHigh << 32) | fad.nFileSizeLow) == dataLen;

        if (!sameSize) {
            HANDLE hFile = CreateFileA(outPath, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
            if (hFile == INVALID_HANDLE_VALUE) fatal("Failed to extract embedded jar");
            DWORD written;
            WriteFile(hFile, p, (DWORD)dataLen, &written, NULL);
            CloseHandle(hFile);
        }

        if (classpathOut[0] != '\0') strncat(classpathOut, ";", classpathCap - strlen(classpathOut) - 1);
        strncat(classpathOut, outPath, classpathCap - strlen(classpathOut) - 1);

        p += dataLen;
    }
}

/* ---------- JRE discovery ---------- */

static BOOL read_registry_javahome(HKEY root, const char *subKey, char *outPath, DWORD outCap) {
    HKEY hKey;
    if (RegOpenKeyExA(root, subKey, 0, KEY_READ, &hKey) != ERROR_SUCCESS) return FALSE;

    char currentVersion[32];
    DWORD cvSize = sizeof(currentVersion);
    if (RegQueryValueExA(hKey, "CurrentVersion", NULL, NULL, (LPBYTE)currentVersion, &cvSize) != ERROR_SUCCESS) {
        RegCloseKey(hKey);
        return FALSE;
    }
    RegCloseKey(hKey);

    char versionKey[300];
    xsnprintf(versionKey, sizeof(versionKey), "%s\\%s", subKey, currentVersion);
    if (RegOpenKeyExA(root, versionKey, 0, KEY_READ, &hKey) != ERROR_SUCCESS) return FALSE;

    DWORD pathSize = outCap;
    LONG rc = RegQueryValueExA(hKey, "JavaHome", NULL, NULL, (LPBYTE)outPath, &pathSize);
    RegCloseKey(hKey);
    return rc == ERROR_SUCCESS;
}

/* Parses a Java version string down to just its major version number.
   Handles both the old "1.8[.0_301]" scheme and the modern "9"/"17"/"17.0.9"
   scheme - registry subkey names and `release`-file JAVA_VERSION values use
   whichever scheme matches how old the JDK/JRE is, and the JavaPanel's
   version dropdowns (jreMin/jreMax) use both too ("1.8" through "22"). */
static int parse_major_version(const char *ver) {
    if (!ver || !*ver) return -1;
    const char *p = ver;
    if (p[0] == '1' && p[1] == '.' && isdigit((unsigned char)p[2])) {
        p += 2;
    }
    if (!isdigit((unsigned char)*p)) return -1;
    return atoi(p);
}

/* Best-effort version sniff for a JAVA_HOME that didn't come with an
   explicit version string attached (the bundled ./jre folder, or
   %JAVA_HOME%): read the `release` file every JDK/JRE 9+ (and most 8u
   updates) ships, falling back to guessing from the folder name
   (e.g. "jdk-17.0.9", "jre1.8.0_301") for the rare install that has neither. */
static int sniff_major_version(const char *javaHome) {
    char releasePath[MAX_PATH];
    xsnprintf(releasePath, sizeof(releasePath), "%s\\release", javaHome);
    HANDLE hFile = CreateFileA(releasePath, GENERIC_READ, FILE_SHARE_READ, NULL,
                                OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (hFile != INVALID_HANDLE_VALUE) {
        char buf[4096];
        DWORD n = 0;
        BOOL ok = ReadFile(hFile, buf, sizeof(buf) - 1, &n, NULL);
        CloseHandle(hFile);
        if (ok) {
            buf[n] = '\0';
            char *p = strstr(buf, "JAVA_VERSION=");
            if (p) {
                p += strlen("JAVA_VERSION=");
                if (*p == '"') p++;
                char ver[32];
                size_t i = 0;
                while (*p && *p != '"' && *p != '\r' && *p != '\n' && i < sizeof(ver) - 1) {
                    ver[i++] = *p++;
                }
                ver[i] = '\0';
                int major = parse_major_version(ver);
                if (major >= 0) return major;
            }
        }
    }

    const char *name = PathFindFileNameA(javaHome);
    for (const char *p = name; *p; p++) {
        if (isdigit((unsigned char)*p)) {
            if (p[0] == '1' && p[1] == '.' && isdigit((unsigned char)p[2])) return atoi(p + 2);
            return atoi(p);
        }
    }
    return -1;
}

typedef struct {
    char path[MAX_PATH];
    int  major; /* -1 = unknown/unparseable */
} JreCandidate;

#define MAX_JRE_CANDIDATES 64

static void add_candidate(JreCandidate *cands, int *count, const char *path, int major) {
    if (*count >= MAX_JRE_CANDIDATES || !path || !*path) return;
    strncpy(cands[*count].path, path, sizeof(cands[*count].path) - 1);
    cands[*count].path[sizeof(cands[*count].path) - 1] = '\0';
    cands[*count].major = major;
    (*count)++;
}

/* Enumerates every version subkey under one JavaSoft registry root (e.g. ALL
   installed JDKs, not just whichever one CurrentVersion happens to point
   at), recording each one's JavaHome path and major version - parsed
   straight from the subkey name, which for these keys IS the version
   string ("1.8", "11", "17.0.9", ...). */
static void collect_registry_candidates(HKEY root, const char *subKey, JreCandidate *cands, int *count) {
    HKEY hKey;
    if (RegOpenKeyExA(root, subKey, 0, KEY_READ, &hKey) != ERROR_SUCCESS) return;

    for (DWORD idx = 0; *count < MAX_JRE_CANDIDATES; idx++) {
        char name[64];
        DWORD nameSize = sizeof(name);
        LONG rc = RegEnumKeyExA(hKey, idx, name, &nameSize, NULL, NULL, NULL, NULL);
        if (rc == ERROR_NO_MORE_ITEMS) break;
        if (rc != ERROR_SUCCESS) continue;

        char versionKey[300];
        xsnprintf(versionKey, sizeof(versionKey), "%s\\%s", subKey, name);
        HKEY hVerKey;
        if (RegOpenKeyExA(root, versionKey, 0, KEY_READ, &hVerKey) != ERROR_SUCCESS) continue;

        char javaHome[MAX_PATH];
        DWORD sz = sizeof(javaHome);
        if (RegQueryValueExA(hVerKey, "JavaHome", NULL, NULL, (LPBYTE)javaHome, &sz) == ERROR_SUCCESS) {
            add_candidate(cands, count, javaHome, parse_major_version(name));
        }
        RegCloseKey(hVerKey);
    }
    RegCloseKey(hKey);
}

static BOOL version_satisfies(int major, int minMajor, int maxMajor) {
    if (major < 0) return FALSE; /* can't verify -> can't promise it satisfies a constraint */
    if (minMajor > 0 && major < minMajor) return FALSE;
    if (maxMajor > 0 && major > maxMajor) return FALSE;
    return TRUE;
}

/* Picks a JRE to launch with.
 *
 * With no min/max constraint configured (minMajor and maxMajor both <= 0),
 * this keeps the original priority order - bundled ./jre, then registry
 * (JDK, then JRE, then legacy JDK key), then JAVA_HOME - and never needs to
 * determine anyone's exact version.
 *
 * With a constraint configured, every known candidate (bundled, every
 * installed registry JDK/JRE, and JAVA_HOME) has its version checked, and
 * the highest one actually satisfying [minMajor, maxMajor] is used. If none
 * qualifies, this returns FALSE so the caller can fail with a message
 * naming the required range, instead of silently launching a JRE that
 * doesn't meet it (which is what happened before this fix - jreMin/jreMax
 * were saved into the build but never actually checked at launch time).
 */
static BOOL find_java_home(int minMajor, int maxMajor, char *outPath, DWORD outCap) {
    char exePath[MAX_PATH];
    GetModuleFileNameA(NULL, exePath, MAX_PATH);
    PathRemoveFileSpecA(exePath);
    char bundled[MAX_PATH];
    xsnprintf(bundled, sizeof(bundled), "%s\\jre", exePath);
    char bundledJavaw[MAX_PATH];
    xsnprintf(bundledJavaw, sizeof(bundledJavaw), "%s\\bin\\javaw.exe", bundled);
    BOOL haveBundled = PathFileExistsA(bundledJavaw);

    char javaHomeEnv[MAX_PATH];
    DWORD envLen = GetEnvironmentVariableA("JAVA_HOME", javaHomeEnv, sizeof(javaHomeEnv));
    BOOL haveEnv = envLen > 0 && envLen < sizeof(javaHomeEnv);

    const char *registryKeys[] = {
        "SOFTWARE\\JavaSoft\\JDK",
        "SOFTWARE\\JavaSoft\\Java Runtime Environment",
        "SOFTWARE\\JavaSoft\\Java Development Kit"
    };

    if (minMajor <= 0 && maxMajor <= 0) {
        if (haveBundled) { xsnprintf(outPath, outCap, "%s", bundled); return TRUE; }
        for (int i = 0; i < 3; i++) {
            if (read_registry_javahome(HKEY_LOCAL_MACHINE, registryKeys[i], outPath, outCap)) return TRUE;
        }
        if (haveEnv) { xsnprintf(outPath, outCap, "%s", javaHomeEnv); return TRUE; }
        return FALSE;
    }

    JreCandidate cands[MAX_JRE_CANDIDATES];
    int count = 0;

    if (haveBundled) add_candidate(cands, &count, bundled, sniff_major_version(bundled));
    for (int i = 0; i < 3; i++) {
        collect_registry_candidates(HKEY_LOCAL_MACHINE, registryKeys[i], cands, &count);
    }
    if (haveEnv) add_candidate(cands, &count, javaHomeEnv, sniff_major_version(javaHomeEnv));

    int bestIdx = -1;
    for (int i = 0; i < count; i++) {
        if (!version_satisfies(cands[i].major, minMajor, maxMajor)) continue;
        if (bestIdx < 0 || cands[i].major > cands[bestIdx].major) bestIdx = i;
    }

    if (bestIdx < 0) return FALSE;
    xsnprintf(outPath, outCap, "%s", cands[bestIdx].path);
    return TRUE;
}

/* ---------- child output capture ----------
   javaw.exe has no console, so an uncaught exception on startup normally
   vanishes into nothing - the process just exits and the user sees a
   spinner then silence. We redirect the child's stdout+stderr into a pipe
   and, if it exits non-zero, show the captured text so the real Java
   exception is visible instead of guessing.

   The pipe is drained on a background thread for the entire lifetime of
   the child so a long-running, chatty app can never fill the OS pipe
   buffer and deadlock on WriteFile - once we hit CAPTURE_CAP we keep
   reading (to keep the pipe from backing up) but just stop copying into
   the buffer. */
static DWORD WINAPI drain_pipe_thread(LPVOID param) {
    DrainCtx *ctx = (DrainCtx *)param;
    char chunk[4096];
    DWORD n;
    while (ReadFile(ctx->hRead, chunk, sizeof(chunk), &n, NULL) && n > 0) {
        if (ctx->used < CAPTURE_CAP) {
            DWORD room = CAPTURE_CAP - ctx->used;
            DWORD take = n < room ? n : room;
            memcpy(ctx->buf + ctx->used, chunk, take);
            ctx->used += take;
        }
        /* bytes beyond CAPTURE_CAP are discarded, but reading them keeps
           the pipe from filling up and blocking the child's writes */
    }
    return 0;
}

/* ---------- entry point ---------- */

int WINAPI WinMain(HINSTANCE hInst, HINSTANCE hPrev, LPSTR cmdLine, int nShow) {
    (void)hInst; (void)hPrev; (void)nShow; /* required by the WinMain signature, unused here */


    /* This process never creates a window of its own - it just launches java.exe
       and blocks on WaitForSingleObject until that child exits. Explorer shows
       the spinning "application is starting" cursor for any newly launched GUI
       process until that process either creates a message queue (by calling
       GetMessage/PeekMessage) or a shell-side timeout elapses. A single
       PeekMessage call is enough to create the queue - it doesn't require an
       actual window or message loop - so do it immediately, before resource
       extraction or the child JVM launch, so Explorer sees "readiness" as soon
       as possible rather than after however long that takes.
       Caveat: Explorer's shell also enforces its own short minimum display time
       for this cursor (commonly ~1-2 seconds) regardless of what the launched
       process does - that part isn't something app code can shorten further. */
    MSG primeMsg;
    PeekMessage(&primeMsg, NULL, 0, 0, PM_NOREMOVE);

    unsigned char *cfgData, *jarsData;
    DWORD cfgSize, jarsSize;

    if (!load_resource("CONFIG", &cfgData, &cfgSize)) fatal("Missing CONFIG resource (build error)");
    if (!load_resource("JARS", &jarsData, &jarsSize)) fatal("Missing JARS resource (build error)");

    AppConfig cfg;
    parse_config(cfgData, cfgSize, &cfg);

    /* single instance check */
    if (cfg.singleInstance) {
        char mutexName[300];
        xsnprintf(mutexName, sizeof(mutexName), "Local\\JLauncher_%s", cfg.appName);
        CreateMutexA(NULL, TRUE, mutexName);
        if (GetLastError() == ERROR_ALREADY_EXISTS) {
            MessageBoxA(NULL, "Application is already running.", cfg.appName, MB_ICONINFORMATION | MB_OK);
            return 0;
        }
    }

    /* temp extraction dir: %TEMP%\<appname>_<hash of JARS blob> */
    char tempBase[MAX_PATH];
    GetTempPathA(sizeof(tempBase), tempBase);
    unsigned long h = hash_bytes(jarsData, jarsSize);
    char destDir[MAX_PATH];
    xsnprintf(destDir, sizeof(destDir), "%s%s_%08lx", tempBase, cfg.appName, h);
    CreateDirectoryA(destDir, NULL);

    char classpath[8192];
    extract_jars(jarsData, jarsSize, destDir, classpath, sizeof(classpath));

    /* locate JRE, honoring the jreMin/jreMax version requirement if one was configured */
    int minMajor = parse_major_version(cfg.jreMin);
    int maxMajor = parse_major_version(cfg.jreMax);

    char javaHome[MAX_PATH];
    if (!find_java_home(minMajor, maxMajor, javaHome, sizeof(javaHome))) {
        if (minMajor > 0 && maxMajor > 0) {
            char msg[512];
            xsnprintf(msg, sizeof(msg),
                     "No installed Java Runtime satisfies the required version range "
                     "(Java %d to Java %d).\n\nInstall a matching JRE/JDK, or adjust the "
                     "JRE version requirement when building this app.", minMajor, maxMajor);
            fatal(msg);
        } else if (minMajor > 0) {
            char msg[512];
            xsnprintf(msg, sizeof(msg),
                     "No installed Java Runtime satisfies the required minimum version "
                     "(Java %d or newer).\n\nInstall a matching JRE/JDK, or adjust the "
                     "JRE version requirement when building this app.", minMajor);
            fatal(msg);
        } else if (maxMajor > 0) {
            char msg[512];
            xsnprintf(msg, sizeof(msg),
                     "No installed Java Runtime satisfies the required maximum version "
                     "(Java %d or older).\n\nInstall a matching JRE/JDK, or adjust the "
                     "JRE version requirement when building this app.", maxMajor);
            fatal(msg);
        }
        fatal("No compatible Java Runtime found.\nInstall a JRE/JDK matching the required version.");
    }
    char javaExe[MAX_PATH];
    xsnprintf(javaExe, sizeof(javaExe), "%s\\bin\\javaw.exe", javaHome);
    if (!PathFileExistsA(javaExe)) fatal("javaw.exe not found under the detected JRE.");

    /* build command line:  "javaw.exe" <jvmArgs, ; -> space> -cp "<classpath>" <mainClass> <passthrough> */
    char jvmArgsSpaced[1024];
    strncpy(jvmArgsSpaced, cfg.jvmArgs, sizeof(jvmArgsSpaced) - 1);
    jvmArgsSpaced[sizeof(jvmArgsSpaced) - 1] = '\0';
    for (char *c = jvmArgsSpaced; *c; c++) if (*c == ';') *c = ' ';

    char cmd[10240];
    xsnprintf(cmd, sizeof(cmd), "\"%s\" %s -cp \"%s\" %s %s",
             javaExe, jvmArgsSpaced, classpath, cfg.mainClass, cmdLine ? cmdLine : "");

    STARTUPINFOA si; PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si)); si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));

    SECURITY_ATTRIBUTES sa;
    ZeroMemory(&sa, sizeof(sa));
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = TRUE;

    HANDLE hReadPipe = NULL, hWritePipe = NULL;
    BOOL havePipe = CreatePipe(&hReadPipe, &hWritePipe, &sa, 65536);
    if (havePipe) {
        /* only the write end should be inherited by the child */
        SetHandleInformation(hReadPipe, HANDLE_FLAG_INHERIT, 0);
        si.dwFlags |= STARTF_USESTDHANDLES;
        si.hStdOutput = hWritePipe;
        si.hStdError = hWritePipe;
        si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    }

    if (!CreateProcessA(NULL, cmd, NULL, NULL, havePipe, 0, NULL, NULL, &si, &pi)) {
        if (havePipe) { CloseHandle(hReadPipe); CloseHandle(hWritePipe); }
        fatal("Failed to start the Java Virtual Machine.");
    }

    char *captured = NULL;
    HANDLE hDrainThread = NULL;
    DrainCtx drainCtx;
    if (havePipe) {
        /* the child owns the write end now - close our copy so ReadFile
           returns EOF once the child (and any of its own children) close
           their handles, instead of blocking forever */
        CloseHandle(hWritePipe);
        captured = (char *)calloc(1, CAPTURE_CAP + 1);
        drainCtx.hRead = hReadPipe;
        drainCtx.buf = captured;
        drainCtx.used = 0;
        hDrainThread = CreateThread(NULL, 0, drain_pipe_thread, &drainCtx, 0, NULL);
    }

    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD exitCode = 0;
    GetExitCodeProcess(pi.hProcess, &exitCode);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);

    if (havePipe) {
        CloseHandle(hReadPipe); /* unblocks the drain thread's ReadFile with EOF */
        if (hDrainThread) {
            WaitForSingleObject(hDrainThread, 2000);
            CloseHandle(hDrainThread);
        }
    }

    if (exitCode != 0 && captured != NULL) {
        char msg[CAPTURE_CAP + 512];
        if (drainCtx.used > 0) {
            xsnprintf(msg, sizeof(msg),
                     "%s exited with code %lu.\r\n\r\nOutput:\r\n%.*s",
                     cfg.appName, (unsigned long)exitCode, (int)drainCtx.used, captured);
        } else {
            xsnprintf(msg, sizeof(msg),
                     "%s exited with code %lu.\r\n\r\n(No output was captured - the JVM itself may have "
                     "failed to start, or the crash happened before any output was written.)",
                     cfg.appName, (unsigned long)exitCode);
        }
        MessageBoxA(NULL, msg, "Application error", MB_ICONERROR | MB_OK);
    }

    if (captured) free(captured);
    return (int)exitCode;
}
