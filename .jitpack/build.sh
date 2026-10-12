#!/usr/bin/env bash
# The JitPack build of every BotMaker module JitPack publishes, run by its jitpack.yml as
# `bash .jitpack/build.sh [maven args]`. Each module's .jitpack/build.sh is a copy of the umbrella's
# tools/jitpack/build.sh, and the release's JitpackScriptGate refuses a module whose copy differs: edit the
# umbrella's, then copy it. Why each step is here: umbrella docs/refactor/43-real-versions.md, § JitPack.
#
# Maven Central answers JitPack's shared IPs with `429 Too Many Requests`, and a tag JitPack built Error stays
# broken: shared v0.2.4 (Maven's own tarball, 2026-10-12) and studio-api v0.4.1 (a plugin, through JitPack's
# Maven 3.6.1, which never retries a 429; 2026-10-05) were lost that way. So this build fetches from Google's
# mirror of Central first, with backoff over several sources, and runs a Maven whose resolver retries a 429.
#
# It runs in jitpack.yml's `install`, the only section whose output the build log shows, and uses nothing of
# the builder's but bash, curl, tar, sha*sum and cc (plain curl flags: one builder's curl lacks
# --retry-all-errors).
set -uo pipefail

GOOGLE_CENTRAL=https://maven-central.storage-download.googleapis.com/maven2

MAVEN_VERSION=3.9.9
MAVEN_SHA512=a555254d6b53d267965a3404ecb14e53c3827c09c3b94b5678835887ab404556bfaf78dcfe03ba76fa2508649dca8531c74bca4d5846513522404d48e8c4ac8b
MAVEN_PATH=org/apache/maven/apache-maven/$MAVEN_VERSION/apache-maven-$MAVEN_VERSION-bin.tar.gz

TEMURIN_TAG=jdk-25.0.4.1%2B1
TEMURIN_SHA256=dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e

here=$(cd "$(dirname "$0")" && pwd)

die() {
    echo "jitpack build: $*" >&2
    exit 1
}

# fetch <dest> <algorithm:hex> <url>... — every URL in turn, in up to six rounds with growing waits between
# them. A download whose checksum differs counts as a failure. BOTMAKER_JITPACK_DELAYS replaces the waits, for
# a local test only.
fetch() {
    local dest=$1 sum=$2
    shift 2
    local -a delays
    read -r -a delays <<<"${BOTMAKER_JITPACK_DELAYS:-10 20 40 60 60}"
    local round url wait
    for ((round = 0; round <= ${#delays[@]}; round++)); do
        for url in "$@"; do
            echo "fetch: $url"
            if curl -fsSL --connect-timeout 20 --max-time 900 -o "$dest" "$url"; then
                if echo "${sum#*:}  $dest" | "${sum%%:*}sum" -c --status; then
                    return 0
                fi
                echo "fetch: checksum mismatch, $url"
            fi
        done
        if ((round < ${#delays[@]})); then
            wait=${delays[round]}
            ((wait > 0)) && wait=$((wait + RANDOM % 10))
            echo "fetch: no source answered, next round in ${wait}s"
            sleep "$wait"
        fi
    done
    rm -f "$dest"
    return 1
}

# unpack <tarball> <dir> — replaces <dir> with the tarball's single top directory.
unpack() {
    rm -rf "$2" && mkdir -p "$2" \
        && tar -xzf "$1" -C "$2" --strip-components=1 --exclude='*/lib/src.zip' \
        && rm -f "$1"
}

# JDK 25: SDKMan's, which jitpack.yml's `jdk` installs, when it worked. On some builders its download fails and
# leaves no JAVA_HOME, so Temurin at a pinned release instead.
if [ -n "${JAVA_HOME:-}" ] && [[ $("$JAVA_HOME/bin/java" -version 2>&1) == *'version "25'* ]]; then
    jdk=$JAVA_HOME
    echo "jdk: SDKMan's, $jdk"
else
    jdk=$HOME/temurin25
    echo "jdk: SDKMan's JDK 25 is missing, fetching Temurin $TEMURIN_TAG"
    fetch "$HOME/temurin25.tar.gz" "sha256:$TEMURIN_SHA256" \
        "https://github.com/adoptium/temurin25-binaries/releases/download/$TEMURIN_TAG/OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz" \
        "https://api.adoptium.net/v3/binary/version/$TEMURIN_TAG/linux/x64/jdk/hotspot/normal/eclipse" \
        || die "no source served Temurin $TEMURIN_TAG"
    unpack "$HOME/temurin25.tar.gz" "$jdk" || die "could not unpack Temurin"
fi

# Maven 3.9.9: its resolver retries a 429 or 503 with backoff and obeys Retry-After, which JitPack's 3.6.1
# does not. BOTMAKER_JITPACK_MAVEN_URLS replaces the sources, for a local test only.
maven=$HOME/maven
if [ -n "${BOTMAKER_JITPACK_MAVEN_URLS:-}" ]; then
    read -r -a maven_urls <<<"$BOTMAKER_JITPACK_MAVEN_URLS"
else
    maven_urls=("$GOOGLE_CENTRAL/$MAVEN_PATH"
        "https://repo.maven.apache.org/maven2/$MAVEN_PATH"
        "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz")
fi
echo "maven: fetching $MAVEN_VERSION"
fetch "$HOME/maven.tar.gz" "sha512:$MAVEN_SHA512" "${maven_urls[@]}" || die "no source served Maven $MAVEN_VERSION"
unpack "$HOME/maven.tar.gz" "$maven" || die "could not unpack Maven"

# Dependencies and plugins from Google's mirror first. These are our Maven's global settings, so JitPack's own
# ~/.m2/settings.xml still applies on top. A settings profile's repositories come before a pom's and before
# Central, which, with the pom's jitpack.io, stays behind as the fallback for anything the mirror lacks. The
# mirror block is the stock 3.9.9 settings' own, kept so a plain-http repository stays refused.
cat >"$maven/conf/settings.xml" <<EOF
<settings>
  <mirrors>
    <mirror>
      <id>maven-default-http-blocker</id>
      <mirrorOf>external:http:*</mirrorOf>
      <name>Pseudo repository to mirror external repositories initially using HTTP.</name>
      <url>http://0.0.0.0/</url>
      <blocked>true</blocked>
    </mirror>
  </mirrors>
  <profiles>
    <profile>
      <id>google-maven-central</id>
      <repositories>
        <repository>
          <id>google-maven-central</id>
          <url>$GOOGLE_CENTRAL</url>
          <snapshots><enabled>false</enabled></snapshots>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>google-maven-central</id>
          <url>$GOOGLE_CENTRAL</url>
          <snapshots><enabled>false</enabled></snapshots>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>google-maven-central</activeProfile>
  </activeProfiles>
</settings>
EOF

# Some builders (kernel 4.14) refuse the statx syscall with EPERM, which Java 22+ uses for every file stat, so
# java can open no jar: `Could not find or load main class org.codehaus.plexus.classworlds.launcher.Launcher`.
# shared v0.2.0 and v0.2.1, basics v0.3.0, v0.3.1 and v0.3.3, session v0.2.0, sdk v1.4.0 were lost that way on
# 2026-10-10. Where java cannot load Maven's launcher, statx.c answers statx from fstatat instead. The launcher,
# loaded with no configuration, exits non-zero naming it; the output is captured rather than piped, because
# under pipefail that exit would read as a refusal.
preload=
launcher=$("$jdk/bin/java" -cp "$(echo "$maven"/boot/plexus-classworlds-*.jar)" \
    org.codehaus.plexus.classworlds.launcher.Launcher 2>&1)
if [[ $launcher == *'classworlds configuration'* ]]; then
    echo "statx: allowed"
else
    cc -shared -fPIC -O2 -o "$HOME/statx.so" "$here/statx.c" || die "could not compile the statx shim"
    preload=$HOME/statx.so
    echo "statx: refused here, preloading $preload"
fi

# A tag's pom names its upstreams' released versions, written by the umbrella release in the release commit
# (umbrella doc 43), so the build needs nothing beside the checkout. A tag cut by hand from main names
# -SNAPSHOT upstreams, which JitPack cannot resolve: the build fails rather than publishing.
# The resolver waits 5, 10, … 30 s on a 429 (or what Retry-After says, at most 60 s): about two minutes for one
# request at worst. Central is asked only for what the mirror lacks (our own artifacts, before jitpack.io), so
# the waits stay well inside JitPack's build time limit.
exec env ${preload:+LD_PRELOAD="$preload"} JAVA_HOME="$jdk" PATH="$jdk/bin:$PATH" \
    "$maven/bin/mvn" install -DskipTests -Dmaven.javadoc.skip=true -B \
    -Daether.connector.http.retryHandler.count=6 \
    -Daether.connector.http.retryHandler.interval=5000 \
    -Daether.connector.http.retryHandler.intervalMax=60000 \
    "$@"
