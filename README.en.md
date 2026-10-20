# EasyBase64

**Important: v1.1.0 uses the new application ID `com.hycnyl.easybase64`. It cannot update 1.0.x in place; settings, task history and folder permissions do not migrate automatically.**


English · [简体中文](README.md)

A native file Base64 encoder and decoder for Android 12 and later. Process arbitrary files, select multiple inputs or scan a folder, and inspect each result. File contents are converted as streams.

**Developers: Hycnyl**

## Features

- **Batch file processing:** select multiple files or scan one folder, optionally including subfolders and preserving their relative paths.
- **Configurable encoding:** Standard / URL-safe alphabets, optional padding, 0 / 64 / 76-character wrapping, LF / CRLF, a final newline, and CERTIFICATE text wrapping.
- **Strict decoding:** independent controls for body whitespace, CERTIFICATE recognition and omitted padding; validation covers padding bits, boundaries and the entire input tail.
- **Protected output:** each batch creates its own output folder, automatically numbers duplicate names, and preserves sources and existing outputs. Failed or cancelled work is cleaned up; unsuccessful cleanup retains its locations.
- **Traceable tasks:** per-file status, bytes read/written, error details and attempt history. Cancel scanning or conversion, and manually retry failed, cancelled or interrupted files.
- **Native interface:** Kotlin, Jetpack Compose, Material 3 and Material 3 Adaptive; English / Simplified Chinese, dynamic system colors, system / light / dark themes, and single- or dual-pane layouts based on available window dimensions.
- **No app networking:** no `INTERNET` permission, account, advertising, telemetry or backend.

Base64 is an encoding, not encryption. CERTIFICATE wrapping is textual packaging and does not establish that the content is a valid certificate.

## Screenshots

| English · Light | Simplified Chinese · Dark |
| --- | --- |
| <img src="docs/screenshots/release-phone-english.png" width="280" alt="EasyBase64 English light-theme phone interface"> | <img src="docs/screenshots/phone-chinese-dark.png" width="280" alt="EasyBase64 Simplified Chinese dark-theme phone interface"> |

<img src="docs/screenshots/release-wide.png" width="900" alt="EasyBase64 wide layout showing the file queue and selected file details together">

More images are available in [docs/screenshots](docs/screenshots). These are emulator screenshots; actual colors follow the system's dynamic palette.

## Install

1. Open this repository's **Releases** and choose a version.
2. Download `EasyBase64-1.1.0-universal.apk`, or the corresponding `EasyBase64-<version>-universal.apk`.
3. Open the APK on your Android device. If prompted, allow your browser or file manager to install apps.

Requires **Android 12 / API 31 or later**. One universal APK includes the required libraries for `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`; no separate v8a / v7a package selection is needed. The application ID is `com.hycnyl.easybase64`.

Before installing, compare the APK's SHA-256 with the checksum file for that release. With Android SDK Build Tools installed, you can also verify its signature:

```sh
sha256sum EasyBase64-1.1.0-universal.apk
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --verbose --print-certs EasyBase64-1.1.0-universal.apk
```

The existing release's certificate fingerprint and APK verification records are in the [test report](docs/TEST_RESULTS.md). **An in-place update must use the same signing key as the installed version.** Debug or self-signed builds may use a different certificate and cannot directly replace the original release.

## Use

1. Select **Encode** or **Decode**, then choose an output folder.
2. Use **Add files** to select multiple inputs, or choose a folder. Enable **Include subfolders** for recursive scanning. An output folder must be selected before scanning an input folder.
3. Review the queue and **Format options**, remove unwanted files, and start processing.
4. Inspect results in the queue. Select a file to view its full name, URIs, errors and attempt history. Open the output folder when the batch finishes.
5. Manually retry failed, cancelled or interrupted files. Retries use the batch's original saved settings; create a new batch to change them.

The current batch's settings and file list are frozen while it runs. One failed file does not stop subsequent files. Cancellation waits for the document provider's reads, writes and cleanup to finish; previously completed results are preserved.

### Output rules

| Input | Output name |
| --- | --- |
| Encode `report.pdf` | `report.pdf.b64` |
| Decode `report.pdf.b64` | `report.pdf` |
| Decode `input.txt`, without a `.b64` suffix | `input.txt.decoded` |

The `.b64` suffix comparison is case-insensitive. Decoding validates content without restricting the input extension. All outputs go into a new batch subfolder beneath the chosen output directory. Multi-file selection uses a flat layout; folder mode preserves relative directories. An output subtree inside the input tree is excluded from scanning. If the provider cannot reliably establish the directory relationship, a different output location is required.

Clearing a queue removes task metadata only. It does not delete sources, completed outputs or residual files. Review unfinished-output details and cleanup records first.

### Format contract

| Setting | Behavior |
| --- | --- |
| Alphabet | Explicit Standard or URL-safe selection; no automatic guessing or mixing. |
| Encoding defaults | Standard, retain `=`, no wrapping, no final newline, no envelope. |
| CERTIFICATE encoding | Forces Standard, padding, 64-character lines and CRLF; does not validate X.509 certificates. |
| Decoding defaults | Ignore SP / TAB / CR / LF, recognize one CERTIFICATE block, require padding. |
| Text encoding | One UTF-8 BOM is permitted at the beginning; UTF-16, a BOM inside the body and non-ASCII body characters are rejected. |
| Strict validation | Padding position/count, tail length, unused padding bits and physical EOF are checked. Trailing junk, missing boundaries, multiple blocks and mismatched labels fail. |

Body whitespace and envelope recognition are independent options. Wrapped bodies containing line breaks still require whitespace tolerance. See the [design specification](docs/DEVELOPMENT_DESIGN.md) for the full contract.

## Build from source

Install a full **JDK 21**, Android SDK platform **37.2** and Build Tools **37.0.0**. Set `ANDROID_HOME`, or set `sdk.dir` in an untracked `local.properties` file. Use the included Gradle Wrapper; a separate global Gradle installation is unnecessary. On Windows, substitute `gradlew.bat` for `./gradlew`.

On Linux x86_64, the existing setup script can prepare the toolchain:

```sh
bash scripts/setup-android.sh
source .toolchains/env.sh
./gradlew :app:assembleDebug
```

The default debug output is `app/build/outputs/apk/debug/app-debug.apk`. The setup script accepts a custom `EASYBASE64_TOOLCHAINS` directory; when using it, source the `env.sh` generated in that directory.

| Component | Pinned version |
| --- | --- |
| Gradle / Android Gradle Plugin | 9.8.2 / 9.4.1 |
| Kotlin / Compose compiler plugin | 2.4.21 |
| Compose BOM / Material 3 Adaptive | 2026.09.00 / 1.3.0 |
| Room / KSP | 2.8.5 / 2.3.12 |
| Compile / target / minimum SDK | 37.2 / 36 / 31 |

Android modules use AGP's built-in Kotlin support. See the [toolchain record](docs/TOOLCHAIN.md) for details.

### Release signing

Release builds enable R8 and resource shrinking:

```sh
./gradlew :app:assembleRelease
```

A local `signing.properties` file can supply signing configuration, for example:

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

Without signing configuration, a release APK does not automatically receive the release signature. Maintainers preparing an update to the original app need its original signing key. **Do not commit private keys, keystores, passwords or `signing.properties`, or upload them as public release assets.** Public certificates and signing fingerprints can be shared for verification.

## Tests and verification scope

```sh
./gradlew :core:test :data:testDebugUnitTest :app:testDebugUnitTest \
  :data:lintDebug :app:lintDebug :app:assembleDebug

# Run with a connected test device or emulator:
./gradlew :data:connectedDebugAndroidTest :app:connectedDebugAndroidTest

# Run the separate large-file check; allow about 4 GiB of temporary free space:
./gradlew :core:largeFileBenchmark
```

The system document-picker fixture expects English system UI. App UI tests explicitly exercise English and Simplified Chinese. Use a dedicated test emulator.

Recorded verification includes 17 core JVM tests, 3 storage naming JVM tests, Android storage/UI instrumentation, a real local SAF provider round trip, and **installation plus UI, foreground-service and file round-trip smoke tests of the actual signed release APK**. Streaming encode/decode and independent SHA-256 comparisons passed for 16 MiB and 1 GiB files on the JVM. App Lint has no errors; retained warnings are documented. The app JVM test task is `NO-SOURCE`; UI checks run as Android instrumentation tests.

Android device verification used an API 31 x86_64 emulator, including phone, low-height landscape, large-font and wide-window layouts. **Physical ARM / ARM64 phones, cloud providers, foldable hardware and every Android version have not been tested.** The JVM benchmark is not an Android SAF throughput claim. Consult the [test report](docs/TEST_RESULTS.md) and [core test record](docs/CORE_TEST_RESULTS.md) for exact tested revisions, subsequent regression runs and limitations.

## Privacy and limitations

Files are accessed through Android's system document picker and Storage Access Framework (SAF), without broad storage access permission. Only URIs, settings and task metadata are stored in the task database, not file bodies. Although the app has no network permission, a selected cloud document provider may upload or download files in its own process.

Conversion uses platform `java.util.Base64` streaming APIs with bounded buffers. Queue metadata still grows with file count. Background processing uses a user-started `dataSync` foreground service and progress notification; indefinite background execution is not guaranteed. After a process or service interruption, reopen the app and retry manually. There is no byte-level resume or automatic background restart.

SAF providers differ in rename support, persistent access and cancellation of blocked I/O. The app stages output, validates the complete input and successful stream closure, then commits by rename or copy. SAF does not universally guarantee atomic commits. Cleanup failures and uncertain interrupted renames retain locations for manual review. See the [storage and recovery notes](docs/STORAGE_NOTES.md).

This release does not include a text workspace, whole-file preview, share target, named presets, cloud sync or certificate validation. Original filenames and metadata cannot be inferred from the Base64 body.

## Project structure and documentation

| Path | Contents |
| --- | --- |
| `core/` | Pure Kotlin/JVM streaming codec, format contract and tests |
| `data/` | SAF, Room queue/attempt records, commit, cancellation and cleanup |
| `app/` | Compose UI, adaptive layouts, system pickers and foreground service |
| [Design specification](docs/DEVELOPMENT_DESIGN.md) | Original development requirements |
| [Implementation decisions](docs/IMPLEMENTATION_NOTES.md) | Architecture and implementation choices |
| [Test report](docs/TEST_RESULTS.md) | Executed checks and limitations |
| [Third-party notices](docs/THIRD_PARTY_NOTICES.md) | Dependency and license information |

[GitHub upload and release guide](docs/GITHUB_UPLOAD.en.md)

## License

The project's original code is licensed under the [MIT License](LICENSE). Copyright © 2026 Hycnyl.

Third-party dependencies retain their respective licenses and copyright notices. See the [third-party notices](docs/THIRD_PARTY_NOTICES.md) and included [Apache License, Version 2.0](docs/licenses/Apache-2.0.txt) for the relevant components.
