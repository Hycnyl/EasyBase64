# EasyBase64 1.0.0 verification

> GitHub 分发说明 / GitHub packaging note: 本仓库保留核心验收证据；完整原始日志、R8 mapping、历史诊断截图和其余截图单独提供为可选验证资料。将验证资料解压到本仓库根目录可恢复全部原始路径。缺少这些补充资料不影响构建。 / This repository keeps the core verification records. Full raw logs, R8 mapping, historical diagnostics and additional screenshots are supplied in an optional verification archive; extract it at the repository root to restore the original paths. The supplement is not needed to build the app.


Date: 2026-10-20. The original design is `DEVELOPMENT_DESIGN.md`. Raw evidence is retained under `docs/verification/`.

## Build and local tests

The final aggregate build completed successfully:

```sh
source /workspace/toolchains/env.sh
./gradlew :core:test :data:testDebugUnitTest :app:testDebugUnitTest \
  :data:lintDebug :app:lintDebug :app:assembleDebug :app:assembleRelease \
  :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest
```

The data instrumentation APK was also built with `:data:assembleDebugAndroidTest`.

| Check | Result |
| --- | --- |
| Core JVM tests | 17 tests, zero failures/errors/skips. Includes RFC vectors, 672 independent encoder option/length combinations, strict decoder contract, bounded boundary parsing, short reads, invalid pad bits, physical EOF, cancellation, read/write/flush failures and bounded output writes. |
| Data JVM tests | 3 tests, zero failures/errors/skips: safe names, output suffixes and collision numbering. |
| App JVM test task | Executed; NO-SOURCE. UI checks are Android instrumentation tests. |
| Data Android Lint | No issues found. |
| App Android Lint | 0 errors, 15 warnings. Remaining warnings concern target SDK 36 versus latest 37, API 33 locale attribute on min 31, unused/localized notification resources, redundant v26 resource qualifier, a newer test-only UI Automator version and optional KTX syntax. |
| Debug APK | Built successfully. |
| Release universal APK | Built successfully with R8 and resource shrinking, 2,401,077 bytes. |
| Signing | APK Signature Scheme v2 verifies; RSA 3072 certificate. V2 is supported by all minimum-supported Android versions. |
| Alignment | `zipalign -c -P 16 -v 4` passes; all native ELF LOAD segments also satisfy 16 KiB alignment. |
| Manifest | Package `com.orbis.easybase64`, min 31, target 36, no INTERNET or broad storage permission. |
| ABI content | One APK contains arm64-v8a, armeabi-v7a, x86 and x86_64 native AndroidX libraries. |

Delivered release APK SHA-256:

`8b1eb2fadddf8600ae755a16a3618c1266e6d5cbb4d0e11868a4d8457cea1123`

Release certificate SHA-256:

`95782d3e7c18c5087e78540f08263cf4b23d4f1b18f1b81f1831fe8fc092adac`

`verification/build.log`, `core-unit-tests.xml`, `data-unit-tests.xml`, `app-lint.txt`, `data-lint.txt`, `apk-signature.txt`, `apk-alignment.txt`, `apk-manifest.txt` and `apk-info.json` contain the underlying evidence. R8 mapping is retained in `verification/release-mapping/`.

## Large-file test

```sh
./gradlew :core:largeFileBenchmark
```

Actual disk files of 16 MiB and 1 GiB were generated, encoded, decoded, reopened and independently SHA-256 hashed. Both comparisons passed. The 1 GiB digest was:

`dca252499f6bfa02d9d16ecaf7e8efc17aab184afe155607bfae22c7d698a949`

Same Linux amd64 JVM 21.0.12.1 process configuration, four available CPUs, 256 MiB maximum heap, fixed codec buffers (64 KiB for input/encoded output, 65,535 bytes for decoded transfer). Sampled heap was approximately 3.06 MiB for 16 MiB and 2.25 MiB for 1 GiB, without growth proportional to body length. This samples Java heap, not complete process RSS. Encoding/decoding the 1 GiB file took 1.947 s / 21.048 s in this build environment; these are not Android throughput promises. See `CORE_TEST_RESULTS.md` and the raw benchmark log. The benchmark was rerun after the Android output-buffer and decoder read-alignment fixes. The final 17-test core suite also verifies confirmed-write progress and failure handling.

## Android device verification

Device: Android 12 / API 31, x86_64 Google APIs emulator, software CPU emulation (no KVM), SwiftShader graphics. Initial device boot and cold test startup were slow; build processes were stopped and display/animation load was reduced for storage tests. During the first UI run, the software-emulated System UI displayed an ANR dialog at 1080×1920 pixels. That run is retained in `verification/app-instrumentation-first-attempt.txt` and its contaminated screenshot under `verification/emulator-issues/`; UI checks were rerun at 540×960/density 210, which preserves the same approximately 411×731 dp phone geometry as 1080×1920/density 420 while reducing rendering load. An initial real-provider test also exposed an invalid privileged test-fixture assumption; its setup was replaced with an actual system-picker URI grant, without changing production permissions.

Storage instrumentation: the **complete 15-test suite passed in 238.449 s, zero failures**, using the APK containing the output-buffer and rename-ownership fixes, before the later decoder read-alignment change. See `verification/data-instrumentation-final.txt` and `data-tested-apk-sha256.txt`.

That complete suite covered:

- 100-file mixed batch, duplicate names, empty file, malformed input, retry with frozen options.
- Withdrawn read permission and temporary-output cleanup.
- Abandoned commit becomes interrupted without rerunning or deleting success.
- Binary round trip, duplicate names and unchanged sources.
- Invalid tail rollback, later-file continuation and persisted results.
- Provider without rename, copy commit and temporary cleanup.
- Unknown-size nonseekable pipe and physical EOF.
- Recursive scan, relative directories and output-subtree exclusion.
- Active cancellation and retry attempt history.
- Failed cleanup, retained residuals and manual cleanup retry.
- Provider write failure never commits partial output.
- 4,000 tasks plus attempts, snapshot larger than 2 MiB, bounded Room rows and restoration.
- Cancellation before execution creates no output or attempt.
- Successful rename revokes the old URI; no obsolete-URI access, no false residual and a subsequent decode batch works.
- Interrupted RENAMED recovery retains only the returned URI and safely cleans it.

After the decoder read-alignment change, four affected storage tests were rerun on the final build and **all passed in 19.133 s**: binary round trip/source preservation, unknown-size nonseekable input, malformed-tail rollback with later-file continuation, and cancellation with retained retry history. See `data-codec-regression-instrumentation-final.txt`; `final-android-tested-apks-sha256.txt` binds the final regression runs to their exact APKs. The other storage tests above exercise unchanged provider, persistence and ownership code.

Four Compose UI tests passed in **176.882 s**: empty-queue start prevention and developer credit, English/Chinese plus theme persistence across activity recreation, required CERTIFICATE option locking, and low-height landscape. These ran before the later decoder alignment and top-bar inset changes; `app-tested-apk-ui-sha256.txt` identifies those APKs. On the final debug build, **two Android platform codec regressions passed in 5.374 s**: 68 decoder boundary combinations across both alphabets and padding settings, and exact encoder output with bounded bulk writes, no downstream scalar writes and retained stream ownership. See `app-platform-instrumentation-final.txt` and `final-android-tested-apks-sha256.txt`.

The final top-bar inset correction passed its enhanced landscape test in **54.567 s**, including opening and closing Settings. `app-landscape-instrumentation-final.txt` and the refreshed landscape screenshot confirm that the settings control remains clear of the system navigation area.

Clean screenshots under `docs/screenshots/` were visually reviewed: English/light phone, Simplified Chinese/dark phone, landscape, and font scale 1.5 before/after scrolling. Text wraps and scrolling keep controls accessible; screenshot helpers now reject obstructing system windows.

The final real-provider instrumentation passed in **47.898 s** through Android's actual document-tree picker and an ordinary persisted URI grant. It verified a 65,537-byte exact encode/decode round trip, source preservation, expected output names, no false rename residual, and acceptance of a new decode batch. See `app-local-saf-instrumentation.txt`.

The **exact signed, R8-shrunk release APK delivered to the user passed installation and the complete UI/SAF/foreground-service smoke test**. After removing the differently signed debug installation, the release installed successfully and cold-launched in 6.733 s on this software-emulated device. Ordinary app buttons and native system pickers selected an input file and separate encode/decode output folders; both operations finished 1/1 successfully. Files were then pulled and checked independently with Python, without using the app codec as the oracle:

| Release smoke artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Original source and restored binary | 65,537 | `42d4633950ee3f4fe2d552dfba382d43488496b046832f15969fd3b8c4b944e0` |
| Base64 output, identical to Python `base64.b64encode` | 87,384 | `897ac5cba01d8aa59cfc731451b5f6425c684d9e2d3975088b06ba050b92c7de` |

The source remained unchanged and no temporary residual files remained. Evidence is under `verification/release-smoke/`, including `tested-release-apk-sha256.txt`, install/launch records, the actual directory-grant dialog, pulled files, the independent verification script and `result.json`. Release screenshots show successful encoding and decoding. After both conversions, the same installed release was rendered at 960×640 pixels / density 128 (approximately 1200×800 dp). `screenshots/release-wide.png` shows the completed queue and selected file details simultaneously; `verification/release-smoke/wide-geometry.txt` records the exact display overrides. The wide screenshot was visually reviewed with no obstructing system dialog. This verifies an emulator window layout, not physical foldable hardware.

An initial storage run passed three cases, including the 100-file mixed-result batch and retry, permission withdrawal, and abandoned-commit recovery, before the execution environment reconnected and stopped the emulator. A resumed run was then stopped deliberately to correct a real Android 12 performance problem: its platform Base64 encoder emits individual characters through `write(int)`. A fixed 64 KiB output buffer now batches provider writes, reports only completed downstream writes, retains caller stream ownership, and does not flush pending bytes after failure. A dedicated Android regression test checks downstream write counts and exact output; the official libcore source excerpt is retained in `verification/android12-encoder-write-behavior.txt`. The actual local provider also exposed a stale-URI cleanup bug: after successful rename it revoked the old temporary URI, producing a false residual that blocked a new batch. Confirmed rename now transfers ownership to the returned URI in memory and in the journal; copy-commit cleanup and ambiguous-rename handling remain distinct. Two provider regressions cover revoked old URIs and interrupted RENAMED recovery. The real-provider round trip then exposed an Android 12 platform decoder bug: a padded two-byte tail crossing a 64 KiB decoded read added two zero bytes. The decode transfer buffer is now 65,535 bytes, divisible by three, so a final quantum fits in one read; no length-based truncation or replacement decoder is used. JVM boundary tests and an Android regression cover both alphabets, padded and unpadded input, and multiple lengths near the affected boundaries. The official source excerpt is retained in `verification/android12-decoder-read-behavior.txt`. Earlier interrupted/failing runner logs are retained for provenance and are not counted as final passing suites.

## Scope and limits

- JVM large-file verification does not claim 1 GiB SAF throughput or whole-process memory on a phone.
- ARM/ARM64 physical phones, vendor-specific Android builds and cloud document providers have not been tested here. The APK packages all four ABIs; actual device verification uses x86_64.
- Android 13+ runtime notification consent, Android 15+ foreground service timeout and foldable hardware hinge behavior are implemented and statically reviewed, but this environment's device test uses API 31.
- SAF cannot guarantee atomic rename, universal provider compatibility or instant cancellation of blocked remote IO. An interrupted rename with an unknown returned URI records the parent/name for manual review; the app does not guess and delete an ambiguous existing file.
- Queue metadata grows with file count. Normalized Room rows avoid one oversized CursorWindow row; batch status transitions still process queue metadata, so huge numbers of tiny files can be slower than a few large files.
- No byte-level resume or automatic background restart. Interrupted work requires manual retry.

The project was not published to an app store or public/remote repository. Local Git history and a portable bundle are delivered separately from private signing material.
