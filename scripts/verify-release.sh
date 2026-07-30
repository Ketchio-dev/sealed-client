#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf '%s\n' \
        "Usage: scripts/verify-release.sh [--repeat-builds N] [--skip-build]" \
        "" \
        "Environment:" \
        "  SEALED_RELEASE_REPEAT_BUILDS  Positive build count (default: 1)." \
        "  SEALED_RELEASE_SKIP_BUILD     Set to 1, true, or yes to verify existing output." \
        "" \
        "Validates the combined Minecraft 1.21.4 + 26.2 release bundle." \
        "Two or more builds additionally verify reproducible release checksums."
}

repeat_builds="${SEALED_RELEASE_REPEAT_BUILDS:-1}"
skip_build="${SEALED_RELEASE_SKIP_BUILD:-false}"

while (($# > 0)); do
    case "$1" in
        --repeat-builds)
            if (($# < 2)); then
                printf 'Missing value for %s\n' "$1" >&2
                exit 2
            fi
            repeat_builds="$2"
            shift 2
            ;;
        --skip-build)
            skip_build=true
            shift
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

if [[ ! "$repeat_builds" =~ ^[1-9][0-9]*$ ]]; then
    printf 'Repeat build count must be a positive integer: %s\n' "$repeat_builds" >&2
    exit 2
fi

case "$skip_build" in
    1|true|TRUE|True|yes|YES|Yes)
        skip_build=true
        ;;
    0|false|FALSE|False|no|NO|No)
        skip_build=false
        ;;
    *)
        printf 'SEALED_RELEASE_SKIP_BUILD must be true/false: %s\n' "$skip_build" >&2
        exit 2
        ;;
esac

if [[ "$skip_build" == true && "$repeat_builds" -ne 1 ]]; then
    printf '%s\n' '--skip-build cannot verify repeated-build reproducibility.' >&2
    exit 2
fi

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd -- "${script_directory}/.." && pwd)"
gradle_wrapper="${repository_root}/gradlew"
release_directory="${repository_root}/build/multiversion-release"

verify_checksums() {
    if [[ ! -f "${release_directory}/SHA256SUMS" ]]; then
        printf 'Missing release checksum manifest: %s\n' \
            "${release_directory}/SHA256SUMS" >&2
        return 1
    fi

    (
        cd "$release_directory"
        if command -v sha256sum >/dev/null 2>&1; then
            sha256sum -c SHA256SUMS
        elif command -v shasum >/dev/null 2>&1; then
            shasum -a 256 -c SHA256SUMS
        else
            printf '%s\n' 'Neither sha256sum nor shasum is available.' >&2
            return 1
        fi
    )
}

verify_sbom() {
    if ! command -v python3 >/dev/null 2>&1; then
        printf '%s\n' 'python3 is required to validate the SBOM structure.' >&2
        return 1
    fi

    declare -a sbom_files=()
    while IFS= read -r sbom_file; do
        sbom_files+=("$sbom_file")
    done < <(
        find "$release_directory" -maxdepth 1 -type f \
            \( -name '*.sbom.json' -o -name '*-bom.json' \) -print
    )

    if ((${#sbom_files[@]} != 2)); then
        printf 'Expected exactly two platform SBOMs, found %d in %s\n' \
            "${#sbom_files[@]}" "$release_directory" >&2
        return 1
    fi

    python3 - "${sbom_files[@]}" <<'PY'
import json
import pathlib
import sys

for argument in sys.argv[1:]:
    path = pathlib.Path(argument)
    with path.open(encoding="utf-8") as source:
        document = json.load(source)

    if document.get("bomFormat") != "CycloneDX":
        raise SystemExit(f"{path.name}: SBOM bomFormat must be CycloneDX")
    if document.get("specVersion") not in {"1.5", "1.6"}:
        raise SystemExit(f"{path.name}: unsupported CycloneDX specVersion")
    if document.get("version") != 1:
        raise SystemExit(f"{path.name}: SBOM document version must be 1")

    component = document.get("metadata", {}).get("component", {})
    for field in ("type", "group", "name", "version"):
        if not isinstance(component.get(field), str) or not component[field]:
            raise SystemExit(
                f"{path.name}: metadata.component.{field} must be a non-empty string"
            )

    components = document.get("components")
    if not isinstance(components, list):
        raise SystemExit(f"{path.name}: components must be an array")
    for index, dependency in enumerate(components):
        for field in ("type", "group", "name", "version"):
            if not isinstance(dependency.get(field), str) or not dependency[field]:
                raise SystemExit(
                    f"{path.name}: components[{index}].{field} is invalid"
                )
        hashes = dependency.get("hashes")
        if not isinstance(hashes, list) or not any(
            item.get("alg") == "SHA-256"
            and isinstance(item.get("content"), str)
            and len(item["content"]) == 64
            for item in hashes
        ):
            raise SystemExit(
                f"{path.name}: components[{index}] lacks a SHA-256 hash"
            )

    print(f"SBOM structure valid: {path.name} ({len(components)} components)")
PY
}

if [[ "$skip_build" == false && ! -x "$gradle_wrapper" ]]; then
    printf 'Executable Gradle wrapper not found: %s\n' "$gradle_wrapper" >&2
    exit 2
fi

reference_checksums=""
for ((iteration = 1; iteration <= repeat_builds; iteration++)); do
    if [[ "$skip_build" == false ]]; then
        printf 'Release verification build %d/%d\n' "$iteration" "$repeat_builds"
        "$gradle_wrapper" clean :common:clean :platform-26.2:clean \
            multiVersionRelease --no-daemon --rerun-tasks
    fi

    verify_checksums
    verify_sbom

    current_checksums="$(LC_ALL=C sort "${release_directory}/SHA256SUMS")"
    if [[ -z "$reference_checksums" ]]; then
        reference_checksums="$current_checksums"
    elif [[ "$current_checksums" != "$reference_checksums" ]]; then
        printf '%s\n' 'Release artifacts are not reproducible across builds.' >&2
        diff <(printf '%s\n' "$reference_checksums") \
            <(printf '%s\n' "$current_checksums") || true
        exit 1
    fi
done

printf 'Release verification passed: %d build(s)\n' "$repeat_builds"
