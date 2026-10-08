package io.github.hectorvent.floci.services.lambda.launcher;

/**
 * Keeps the image's runtime entrypoint while executing the supplied bootstrap in
 * its package or layer directory. Moving the supplied file changes its {@code $0}
 * and breaks runtimes that locate companion files relative to that path.
 */
final class ProvidedRuntimeBootstrap {

    static final String SCRIPT = """
            #!/bin/sh
            set -eu
            if [ -e /var/task/bootstrap ]; then
                exec /var/task/bootstrap "$@"
            fi
            if [ -e /opt/bootstrap ]; then
                exec /opt/bootstrap "$@"
            fi
            echo 'Runtime.InvalidEntrypoint: bootstrap is absent from the package and layers' >&2
            exit 127
            """;

    private ProvidedRuntimeBootstrap() {
    }
}
