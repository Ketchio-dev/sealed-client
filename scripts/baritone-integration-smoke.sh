#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BARITONE_VERSION="${BARITONE_VERSION:-1.13.1}"
ASSET_NAME="baritone-api-fabric-${BARITONE_VERSION}.jar"
RELEASE_BASE="https://github.com/cabaletta/baritone/releases/download/v${BARITONE_VERSION}"
SMOKE_DIRECTORY="$(mktemp -d /tmp/sealed-baritone-smoke.XXXXXX)"

cleanup() {
    if [[ "${SMOKE_DIRECTORY}" == /tmp/sealed-baritone-smoke.* ]]; then
        rm -rf -- "${SMOKE_DIRECTORY}"
    fi
}
trap cleanup EXIT

curl --fail --location --silent --show-error \
    --output "${SMOKE_DIRECTORY}/${ASSET_NAME}" \
    "${RELEASE_BASE}/${ASSET_NAME}"
curl --fail --location --silent --show-error \
    --output "${SMOKE_DIRECTORY}/checksums.txt" \
    "${RELEASE_BASE}/checksums.txt"

CHECKSUM_LINE="$(
    awk -v asset="${ASSET_NAME}" '$2 == asset { print; exit }' \
        "${SMOKE_DIRECTORY}/checksums.txt"
)"
if [[ -z "${CHECKSUM_LINE}" ]]; then
    echo "Official checksum entry is missing for ${ASSET_NAME}" >&2
    exit 1
fi

(
    cd "${SMOKE_DIRECTORY}"
    printf '%s\n' "${CHECKSUM_LINE}" | shasum -a 1 --check -
)

SUPPORT_JAR="${SMOKE_DIRECTORY}/nether-pathfinder-1.4.1.jar"
unzip -p "${SMOKE_DIRECTORY}/${ASSET_NAME}" \
    META-INF/jars/nether-pathfinder-1.4.1.jar > "${SUPPORT_JAR}"

cd "${PROJECT_ROOT}"
./gradlew --no-daemon --dependency-verification lenient runClientGameTest \
    "-PbaritoneSmokeJar=${SMOKE_DIRECTORY}/${ASSET_NAME}" \
    "-PbaritoneSmokeSupportJar=${SUPPORT_JAR}" \
    --console=plain
