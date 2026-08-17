// Observe-only EndpointSecurity self-protection prototype for Otterling (macOS).
//
// PURPOSE (milestone 1): find out whether an *unsigned* ES client can even be created on this
// machine's custom-SIP config, and -- if so -- watch the exact delete/kill operations a real
// build would deny. It NEVER denies anything: every AUTH event is answered ES_AUTH_RESULT_ALLOW
// immediately. That's deliberate. An ES client that returns DENY on its own files, with a wrong
// whitelist, can lock you out of normal admin operations and can only be cleared by booting to
// Recovery -- so we prove the whitelist in a log before anything is ever blocked.
//
// WHY C, NOT the SwiftPM app: es_new_client normally requires the restricted
// com.apple.developer.endpoint-security.client entitlement. On a SIP-disabled machine an unsigned
// *daemon* (not a system extension) can create the client without it -- see the Apple dev-forum
// threads in the repo discussion. Building this as a tiny freestanding C binary keeps that test
// isolated from the signed app bundle.
//
// BUILD:  Scripts/build_es_prototype.sh          (or: clang -O2 -o es_selfprotect es_selfprotect.c
//                                                  -lEndpointSecurity -lbsm)
// RUN:    sudo ./es_selfprotect                  (must be root; Ctrl-C to stop)
//
// If es_new_client fails, the program prints the precise result code and what it means, which is
// the entire answer to "is this even possible here?".

#include <EndpointSecurity/EndpointSecurity.h>
#include <bsm/libbsm.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

// The set of paths a real build would protect from deletion. Prefix match: anything at or under
// one of these is "ours". Kept in one place so the whitelist is auditable at a glance.
static const char *kProtectedPathPrefixes[] = {
    "/Applications/Otterling.app",
    "/usr/local/bin/focuslockctl",
    "/Library/Application Support/FocusLock",
};

// Executable-name fragments identifying a process a real build would protect from being killed.
static const char *kProtectedProcessFragments[] = {
    "FocusLockHelperd",
    "FocusLockWatchdog",
    "Otterling.app/Contents/MacOS/",
};

static atomic_bool g_stop = false;

static void handle_sigint(int sig) {
    (void)sig;
    atomic_store(&g_stop, true);
}

// es_string_token_t is not NUL-terminated; copy into a bounded buffer for printf/strstr.
static void copy_token(es_string_token_t tok, char *out, size_t out_len) {
    size_t n = tok.length < (out_len - 1) ? tok.length : (out_len - 1);
    if (tok.data && n > 0) memcpy(out, tok.data, n);
    out[n] = '\0';
}

static bool path_is_protected(const char *path) {
    for (size_t i = 0; i < sizeof(kProtectedPathPrefixes) / sizeof(kProtectedPathPrefixes[0]); i++) {
        if (strncmp(path, kProtectedPathPrefixes[i], strlen(kProtectedPathPrefixes[i])) == 0) {
            return true;
        }
    }
    return false;
}

static bool process_is_protected(const char *exec_path) {
    for (size_t i = 0; i < sizeof(kProtectedProcessFragments) / sizeof(kProtectedProcessFragments[0]); i++) {
        if (strstr(exec_path, kProtectedProcessFragments[i]) != NULL) {
            return true;
        }
    }
    return false;
}

static void log_line(const char *verdict, const char *what) {
    time_t now = time(NULL);
    struct tm tm_now;
    localtime_r(&now, &tm_now);
    char ts[32];
    strftime(ts, sizeof(ts), "%H:%M:%S", &tm_now);
    printf("[%s] %-11s %s\n", ts, verdict, what);
    fflush(stdout);
}

static const char *new_client_error(es_new_client_result_t r) {
    switch (r) {
        case ES_NEW_CLIENT_RESULT_ERR_NOT_ENTITLED:
            return "NOT_ENTITLED -- the binary lacks the endpoint-security.client entitlement AND "
                   "SIP isn't permissive enough to waive it. This machine can't run an unsigned ES "
                   "client; the ES route is closed here.";
        case ES_NEW_CLIENT_RESULT_ERR_NOT_PERMITTED:
            return "NOT_PERMITTED -- needs Full Disk Access (TCC) for this binary/Terminal. Grant "
                   "it in System Settings > Privacy & Security > Full Disk Access, then re-run.";
        case ES_NEW_CLIENT_RESULT_ERR_NOT_PRIVILEGED:
            return "NOT_PRIVILEGED -- must run as root. Use sudo.";
        case ES_NEW_CLIENT_RESULT_ERR_TOO_MANY_CLIENTS:
            return "TOO_MANY_CLIENTS -- another ES client is using the slot; stop it and retry.";
        case ES_NEW_CLIENT_RESULT_ERR_INTERNAL:
            return "INTERNAL -- the framework failed to init (often the ES subsystem being blocked "
                   "on this custom-SIP config).";
        case ES_NEW_CLIENT_RESULT_ERR_INVALID_ARGUMENT:
            return "INVALID_ARGUMENT.";
        default:
            return "unknown result code.";
    }
}

int main(void) {
    printf("Otterling ES self-protection prototype (OBSERVE-ONLY -- nothing is ever blocked)\n");
    printf("Attempting es_new_client()...\n");
    fflush(stdout);

    es_client_t *client = NULL;
    es_new_client_result_t result = es_new_client(&client, ^(es_client_t *c, const es_message_t *msg) {
        // AUTH events carry a hard deadline; miss it and the OS kills this client (and can stall
        // whatever triggered the event). Observe mode ALWAYS allows, immediately -- the only safe
        // default while we're still proving the whitelist.
        switch (msg->event_type) {
            case ES_EVENT_TYPE_AUTH_UNLINK: {
                char path[4096];
                copy_token(msg->event.unlink.target->path, path, sizeof(path));
                if (path_is_protected(path)) {
                    char sender[4096];
                    copy_token(msg->process->executable->path, sender, sizeof(sender));
                    char line[8300];
                    snprintf(line, sizeof(line), "delete of %s  (by %s, pid %d)",
                             path, sender, audit_token_to_pid(msg->process->audit_token));
                    log_line("WOULD-DENY", line);
                }
                es_respond_auth_result(c, msg, ES_AUTH_RESULT_ALLOW, false);
                break;
            }
            case ES_EVENT_TYPE_AUTH_SIGNAL: {
                char target[4096];
                copy_token(msg->event.signal.target->executable->path, target, sizeof(target));
                if (process_is_protected(target)) {
                    char sender[4096];
                    copy_token(msg->process->executable->path, sender, sizeof(sender));
                    char line[8400];
                    snprintf(line, sizeof(line), "signal %d to %s  (by %s, pid %d)",
                             msg->event.signal.sig, target, sender,
                             audit_token_to_pid(msg->process->audit_token));
                    log_line("WOULD-DENY", line);
                }
                es_respond_auth_result(c, msg, ES_AUTH_RESULT_ALLOW, false);
                break;
            }
            default:
                break;
        }
    });

    if (result != ES_NEW_CLIENT_RESULT_SUCCESS) {
        printf("\nes_new_client FAILED: %s\n", new_client_error(result));
        return 1;
    }
    printf("es_new_client SUCCEEDED -- an unsigned ES client CAN run on this config.\n");

    es_event_type_t events[] = {
        ES_EVENT_TYPE_AUTH_UNLINK,
        ES_EVENT_TYPE_AUTH_SIGNAL,
    };
    if (es_subscribe(client, events, sizeof(events) / sizeof(events[0])) != ES_RETURN_SUCCESS) {
        printf("es_subscribe FAILED.\n");
        es_delete_client(client);
        return 1;
    }

    printf("Subscribed to AUTH_UNLINK + AUTH_SIGNAL. Watching for delete/kill of:\n");
    for (size_t i = 0; i < sizeof(kProtectedPathPrefixes) / sizeof(kProtectedPathPrefixes[0]); i++) {
        printf("    %s\n", kProtectedPathPrefixes[i]);
    }
    printf("Try `rm -rf /Applications/Otterling.app` or killing FocusLockHelperd in another\n");
    printf("terminal -- it will SUCCEED (observe mode) but show up here as WOULD-DENY. Ctrl-C to stop.\n\n");
    fflush(stdout);

    signal(SIGINT, handle_sigint);
    while (!atomic_load(&g_stop)) {
        struct timespec ts = {.tv_sec = 0, .tv_nsec = 200 * 1000 * 1000};
        nanosleep(&ts, NULL);
    }

    printf("\nStopping -- unsubscribing and releasing the ES client.\n");
    es_unsubscribe_all(client);
    es_delete_client(client);
    return 0;
}
