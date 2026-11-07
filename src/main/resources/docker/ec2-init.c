#define _GNU_SOURCE
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* A static, architecture-native bridge to init; no service or AWS behavior lives here. */
#define TIME_READY "/run/floci-time-ready"
#define INIT_READY "/run/floci-init-ready"

static void fail(const char *message) {
    perror(message);
    exit(EXIT_FAILURE);
}

static void require(int condition, const char *message) {
    if (!condition) {
        fprintf(stderr, "%s\n", message);
        exit(EXIT_FAILURE);
    }
}

static void boot(char **command) {
    require(getpid() == 1, "Native EC2 init must be PID 1");
    if (unshare(CLONE_NEWTIME) != 0) {
        fail("Cannot create private EC2 time namespace");
    }
    int ready = open(TIME_READY, O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, 0600);
    if (ready < 0 || close(ready) != 0) {
        fail("Cannot signal private EC2 time namespace");
    }
    struct timespec started, current;
    if (clock_gettime(CLOCK_MONOTONIC, &started) != 0) {
        fail("Cannot read boot-gate deadline");
    }
    while (access(INIT_READY, F_OK) != 0) {
        if (errno != ENOENT) {
            fail("Cannot inspect EC2 init gate");
        }
        if (clock_gettime(CLOCK_MONOTONIC, &current) != 0) {
            fail("Cannot read boot-gate deadline");
        }
        require(current.tv_sec - started.tv_sec < 120, "EC2 init gate was not released");
        struct timespec delay = {.tv_sec = 0, .tv_nsec = 50000000};
        if (nanosleep(&delay, NULL) != 0 && errno != EINTR) {
            fail("Cannot wait for EC2 init gate");
        }
    }
    int namespace = open("/proc/self/ns/time_for_children", O_RDONLY | O_CLOEXEC);
    if (namespace < 0 || setns(namespace, CLONE_NEWTIME) != 0) {
        fail("Cannot enter private EC2 time namespace");
    }
    if (close(namespace) != 0 || unshare(CLONE_NEWCGROUP) != 0) {
        fail("Cannot enter delegated EC2 service cgroup");
    }
    execv(command[0], command);
    fail("Cannot execute native EC2 init");
}

static int verify_guest(long pid, const char *container) {
    require(pid > 1 && pid <= INT_MAX && strlen(container) == 64, "Invalid EC2 guest identity");
    for (const char *p = container; *p; p++) {
        require(isxdigit((unsigned char)*p), "Invalid EC2 container ID");
    }
    char path[80];
    snprintf(path, sizeof(path), "/proc/%ld", pid);
    int directory = open(path, O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (directory < 0) {
        fail("Cannot pin EC2 guest identity");
    }
    /* Proc directory FDs retain the original process identity even if its PID is reused. */
    FILE *input = fdopen(openat(directory, "cgroup", O_RDONLY | O_CLOEXEC), "r");
    if (input == NULL) {
        fail("Cannot inspect EC2 guest cgroup");
    }
    char *line = NULL;
    size_t size = 0;
    int owned = 0;
    while (getline(&line, &size, input) >= 0) {
        owned |= strstr(line, container) != NULL;
    }
    require(!ferror(input) && owned, "PID does not belong to the EC2 container");
    fclose(input);
    input = fdopen(openat(directory, "status", O_RDONLY | O_CLOEXEC), "r");
    if (input == NULL) {
        fail("Cannot inspect EC2 guest PID namespace");
    }
    long last_pid = 0;
    while (getline(&line, &size, input) >= 0) {
        if (strncmp(line, "NSpid:", 6) == 0) {
            char *cursor = line + 6;
            while (*cursor) {
                while (isspace((unsigned char)*cursor)) {
                    cursor++;
                }
                if (!*cursor) {
                    break;
                }
                char *end;
                last_pid = strtol(cursor, &end, 10);
                require(end != cursor, "Cannot parse EC2 guest PID namespace");
                cursor = end;
            }
            break;
        }
    }
    require(!ferror(input) && last_pid == 1, "EC2 guest is not its namespace's init");
    free(line);
    fclose(input);
    return directory;
}

static void configure_time(const char *pid_text, const char *container) {
    char *end;
    errno = 0;
    long pid = strtol(pid_text, &end, 10);
    require(errno == 0 && end != pid_text && *end == '\0', "Invalid EC2 guest PID");
    int guest = verify_guest(pid, container);
    struct timespec monotonic, boottime;
    if (clock_gettime(CLOCK_MONOTONIC, &monotonic) != 0
            || clock_gettime(CLOCK_BOOTTIME, &boottime) != 0) {
        fail("Cannot read EC2 clock origins");
    }
    int offsets = openat(guest, "timens_offsets", O_WRONLY | O_CLOEXEC);
    if (offsets < 0) {
        fail("Cannot open EC2 child clock offsets");
    }
    /* The kernel accepts offsets only in an unentered child time namespace.
       Initial/host and already-entered namespaces have frozen offsets. */
    char values[160];
    int length = snprintf(values, sizeof(values), "monotonic %ld 0\nboottime %ld 0\n",
                          -monotonic.tv_sec, -boottime.tv_sec);
    require(length > 0 && (size_t)length < sizeof(values), "EC2 clock offsets overflowed");
    if (write(offsets, values, (size_t)length) != length || close(offsets) != 0 || close(guest) != 0) {
        fail("Cannot initialize private EC2 clocks");
    }
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--version") == 0) {
        puts("floci-ec2-init 1");
        return EXIT_SUCCESS;
    }
    if (argc == 4 && strcmp(argv[1], "--configure-time") == 0) {
        configure_time(argv[2], argv[3]);
        return EXIT_SUCCESS;
    }
    require(argc >= 3 && strcmp(argv[1], "--boot") == 0,
            "Usage: floci-ec2-init --boot /sbin/init | --configure-time PID CONTAINER_ID");
    boot(argv + 2);
    return EXIT_FAILURE;
}
