#!/usr/bin/env bash
# Reproducible Linux x86_64 toolchain setup. Downloads official stable releases.
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
toolchain_dir="${EASYBASE64_TOOLCHAINS:-$project_dir/.toolchains}"
sdk_dir="$toolchain_dir/android-sdk"
jdk_dir="$toolchain_dir/jdk-21.0.12.1+1"

if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
  printf '%s\n' 'This bootstrap supports Linux x86_64. On other hosts install JDK 21 and Android SDK platform 37.2 with build-tools 37.0.0, then use ./gradlew.' >&2
  exit 1
fi
for command_name in curl unzip tar sha256sum; do
  command -v "$command_name" >/dev/null || { printf 'Install required command: %s\n' "$command_name" >&2; exit 1; }
done
mkdir -p "$toolchain_dir"
toolchain_dir="$(cd -- "$toolchain_dir" && pwd)"
sdk_dir="$toolchain_dir/android-sdk"
jdk_dir="$toolchain_dir/jdk-21.0.12.1+1"

download_verified() {
  local url="$1" destination="$2" expected_sha256="$3"
  if [[ ! -f "$destination" ]] || ! printf '%s  %s\n' "$expected_sha256" "$destination" | sha256sum --check --status; then
    curl --fail --location --retry 3 "$url" --output "$destination.part"
    printf '%s  %s\n' "$expected_sha256" "$destination.part" | sha256sum --check
    mv -- "$destination.part" "$destination"
  fi
}

if [[ ! -x "$jdk_dir/bin/javac" ]]; then
  download_verified \
    'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz' \
    "$toolchain_dir/jdk21.tar.gz" \
    'ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94'
  tar -xf "$toolchain_dir/jdk21.tar.gz" -C "$toolchain_dir"
fi
export JAVA_HOME="$jdk_dir"
export PATH="$JAVA_HOME/bin:$PATH"
# Preserve the host's trusted certificate authorities (including a managed
# HTTPS proxy CA) when switching from the system JVM to this downloaded JDK.
# This copies public trust anchors, not private keys or account credentials.
if [[ -r /etc/ssl/certs/java/cacerts ]]; then
  cp -- /etc/ssl/certs/java/cacerts "$JAVA_HOME/lib/security/cacerts"
fi

if [[ ! -x "$sdk_dir/cmdline-tools/latest/bin/sdkmanager" ]]; then
  download_verified \
    'https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip' \
    "$toolchain_dir/cmdline-tools.zip" \
    '0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8'
  mkdir -p "$sdk_dir/cmdline-tools"
  unzip -q "$toolchain_dir/cmdline-tools.zip" -d "$sdk_dir/cmdline-tools"
  mv -- "$sdk_dir/cmdline-tools/cmdline-tools" "$sdk_dir/cmdline-tools/latest"
fi
export ANDROID_HOME="$sdk_dir"
export ANDROID_SDK_ROOT="$sdk_dir"
export PATH="$sdk_dir/platform-tools:$sdk_dir/cmdline-tools/latest/bin:$PATH"

# SDK command-line tools 23 delegates sdkmanager to Android CLI; licenses are
# handled by that installer. No preview channel or unverified mirror is used.
sdkmanager --sdk_root="$sdk_dir" 'platforms;android-37.2' 'build-tools;37.0.0' 'platform-tools'

printf 'sdk.dir=%s\n' "$sdk_dir" > "$project_dir/local.properties"
{
  printf 'export JAVA_HOME=%q\n' "$jdk_dir"
  printf 'export ANDROID_HOME=%q\n' "$sdk_dir"
  printf 'export ANDROID_SDK_ROOT=%q\n' "$sdk_dir"
  printf 'export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"\n'
} > "$toolchain_dir/env.sh"

"$JAVA_HOME/bin/javac" -version
(cd -- "$project_dir" && ./gradlew --version)
printf '\nReady. Run:\n  source %q\n  ./gradlew :core:test :data:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug\n' "$toolchain_dir/env.sh"
