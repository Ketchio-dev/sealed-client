#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf '%s\n' \
        "Usage: scripts/competitive-integration-gate.sh [--iterations N]" \
        "" \
        "Environment:" \
        "  B2T_COMPETITIVE_GATE_ITERATIONS  Positive iteration count (default: 1)." \
        "" \
        "Runs root, common, and Minecraft 26.2 integration/unit matrices without" \
        "a persistent Gradle daemon. Every iteration reruns the selected tasks."
}

iterations="${B2T_COMPETITIVE_GATE_ITERATIONS:-1}"

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

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd -- "${script_directory}/.." && pwd)"
gradle_wrapper="${repository_root}/gradlew"

if [[ ! -x "$gradle_wrapper" ]]; then
    printf 'Executable Gradle wrapper not found: %s\n' "$gradle_wrapper" >&2
    exit 2
fi

cd "$repository_root"
for ((iteration = 1; iteration <= iterations; iteration++)); do
    printf 'Competitive integration gate iteration %d/%d\n' \
        "$iteration" "$iterations"
    "$gradle_wrapper" \
        --no-daemon \
        --rerun-tasks \
        :test \
        :common:test \
        :platform-26.2:test
done

printf 'Competitive integration gate passed: %d iteration(s)\n' "$iterations"
