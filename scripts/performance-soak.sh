#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf '%s\n' \
        "Usage: scripts/performance-soak.sh [--iterations N] [--test PATTERN]..." \
        "" \
        "Environment:" \
        "  SEALED_SOAK_ITERATIONS  Positive iteration count (default: 3)." \
        "  SEALED_SOAK_TESTS       Comma-separated Gradle test patterns." \
        "" \
        "When no test patterns are supplied, all *Performance* tests are run."
}

iterations="${SEALED_SOAK_ITERATIONS:-3}"
declare -a test_patterns=()

if [[ -n "${SEALED_SOAK_TESTS:-}" ]]; then
    IFS=',' read -r -a test_patterns <<< "${SEALED_SOAK_TESTS}"
fi

while (($# > 0)); do
    case "$1" in
        -n|--iterations)
            if (($# < 2)); then
                printf 'Missing value for %s\n' "$1" >&2
                exit 2
            fi
            iterations="$2"
            shift 2
            ;;
        -t|--test)
            if (($# < 2)); then
                printf 'Missing value for %s\n' "$1" >&2
                exit 2
            fi
            test_patterns+=("$2")
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf 'Unknown argument: %s\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [[ ! "$iterations" =~ ^[1-9][0-9]*$ ]]; then
    printf 'Iteration count must be a positive integer: %s\n' "$iterations" >&2
    exit 2
fi

if ((${#test_patterns[@]} == 0)); then
    test_patterns=("dev.sealedclient.performance.*Performance*")
fi

for pattern in "${test_patterns[@]}"; do
    if [[ -z "$pattern" ]]; then
        printf 'Test patterns must not be empty.\n' >&2
        exit 2
    fi
done

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd -- "${script_directory}/.." && pwd)"
gradle_wrapper="${repository_root}/gradlew"
if [[ ! -x "$gradle_wrapper" ]]; then
    printf 'Executable Gradle wrapper not found: %s\n' "$gradle_wrapper" >&2
    exit 2
fi

declare -a test_arguments=()
for pattern in "${test_patterns[@]}"; do
    test_arguments+=(--tests "$pattern")
done

cd "$repository_root"
for ((iteration = 1; iteration <= iterations; iteration++)); do
    printf 'Performance soak iteration %d/%d\n' "$iteration" "$iterations"
    "$gradle_wrapper" :test \
        --no-daemon \
        --rerun-tasks \
        "${test_arguments[@]}"
done

printf 'Performance soak passed: %d iteration(s)\n' "$iterations"
