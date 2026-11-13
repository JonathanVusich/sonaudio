#!/usr/bin/env bash
# Shared by the scripts in this directory that run on the host: finds the repository, builds the reference image when it
# is missing or built from another Dockerfile, and runs a script from this directory inside it.

reference_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd "$reference_dir/.." && pwd)
image=sonaudio-ref

# The Dockerfile's SHA-256, which the image carries as a label, so a changed Dockerfile gets a new image.
dockerfile_sum() {
    sha256sum "$reference_dir/Dockerfile" | cut -d' ' -f1
}

ensure_image() {
    local sum built
    sum=$(dockerfile_sum)
    built=$(docker image inspect "$image" --format '{{ index .Config.Labels "sonaudio.dockerfile" }}' 2>/dev/null || true)
    if [[ $built != "$sum" ]]; then
        echo "building $image"
        docker build --label "sonaudio.dockerfile=$sum" -t "$image" "$reference_dir"
    fi
}

# Git Bash on Windows hands docker POSIX-style paths it can't mount.
host_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$1"
    else
        echo "$1"
    fi
}

# run_in_image <script in this directory> <args...>: runs it in the image, with the repository at /repo and the FATE
# directory (if FATE_DIR is set) at /fate.
run_in_image() {
    local script=$1
    shift
    local mounts=(-v "$(host_path "$repo_dir"):/repo")
    if [[ -n ${FATE_DIR:-} ]]; then
        mounts+=(-v "$(host_path "$FATE_DIR"):/fate")
    fi
    MSYS_NO_PATHCONV=1 docker run --rm --user "$(id -u):$(id -g)" "${mounts[@]}" "$image" bash "/repo/reference/$script" "$@"
}
