# Core codec verification / 核心编解码验证

> GitHub 分发说明 / GitHub packaging note: 本仓库保留核心验收证据；完整原始日志、R8 mapping、历史诊断截图和其余截图单独提供为可选验证资料。将验证资料解压到本仓库根目录可恢复全部原始路径。缺少这些补充资料不影响构建。 / This repository keeps the core verification records. Full raw logs, R8 mapping, historical diagnostics and additional screenshots are supplied in an optional verification archive; extract it at the repository root to restore the original paths. The supplement is not needed to build the app.


Date / 日期: 2026-10-20 UTC

## JVM unit tests

Command executed from the project root (the cloud session first sourced
`/workspace/toolchains/env.sh` to select the installed JDK and Android SDK):

```sh
./gradlew :core:test --console=plain
```

The parent build initially invoked `:core:test` together with the Android module checks. After failure-progress, Android scalar-output buffering, and Android decoder quantum-alignment corrections, `:core:test :core:largeFileBenchmark` was rerun successfully (45 seconds for the combined Gradle command).
JUnit XML records **17 test methods, 0 failures, 0 errors, 0 skipped**;
latest suite execution time **0.892 seconds**. Most methods exercise multiple cases.
The retained machine-readable result is
`core/build/test-results/test/TEST-com.orbis.easybase64.core.Base64CodecTest.xml`.
The HTML report is `core/build/reports/tests/test/index.html`.

Coverage:

| Design case | Executed coverage |
| --- | --- |
| C01 | All RFC 4648 text vectors, empty input, known Standard/URL-safe binary vectors |
| C02 | 48 encoding option combinations × 14 boundary sizes, compared against independent `java.util.Base64` output with test-side formatting; exact 64/76 line endings and empty/final-newline cases |
| C03 | All four whitespace/envelope setting combinations, separate ASCII whitespace rules, alphabet mismatch |
| C04 | Exact CERTIFICATE output, forced wrapper options, BOM only at physical byte zero, outer blank lines, UTF-16/non-ASCII rejection |
| C05 | Missing/mismatched boundaries, nested/multiple blocks, text outside a block, malformed line endings, 256-byte boundary limit |
| C06 | Padding position/count, missing padding, length remainder one, canonical zero unused pad bits |
| C07 | Extra data after padding, garbage after END, second block, read failure encountered only after padded body |
| C08 | One-byte and irregular short reads with BOM, CRLF, boundaries and final groups crossing reads |
| C09 | 3 MiB unwrapped input, 60 additional Standard/URL-safe and padded/unpadded tail cases around 65,535- and 131,070-byte boundaries, one million leading whitespace bytes without buffering a line |
| C10 (core layer) | Read/write/flush errors, cancellation, monotonic final byte counts on success and failure, progress-callback failure suppression preserving the original codec error, caller ownership of input/output streams |

Errors also have physical-byte-offset checks, including BOM and replayed boundary
prefixes. The codec intentionally leaves original stream closing to the SAF
owner; integration tests are responsible for close failures and output cleanup.

The production conversion path has no whole-file `readBytes`, `readText` or
`readLine`; it uses fixed 64 KiB buffers and at most a 256-byte boundary candidate.
A fixed 64 KiB output buffer batches Android libcore encoder scalar writes before they reach the document provider. A JVM sink regression rejects scalar provider writes, checks bounded bulk-call counts including padded tails and CERTIFICATE formatting, and verifies that failure does not flush a pending partial buffer. Android-specific regression evidence is recorded separately. Byte conversion is performed by `java.util.Base64` streaming wrappers. Decoding
explicitly drains and validates the physical input after the platform decoder
stops, including padded input.

The decode transfer buffer is **65,535 bytes**, divisible by the three decoded
bytes per complete Base64 quantum. Android 12's platform streaming decoder can
append two zero bytes when its padded two-byte tail is split across reads: after
it drains a saved byte, it processes EOF again with stale group state. Aligned
read sizes keep each complete group together and leave enough space for the final
one or two bytes, avoiding that branch without truncation, guessing lengths, or
reimplementing Base64. This applies to unknown-size streams as well. The actual
Android regression covers the platform implementation, while the JVM test checks
60 boundary/option combinations against an independent array-decoder oracle.
The behavior can be inspected in the [Android 12 libcore source](https://android.googlesource.com/platform/libcore/+/refs/tags/android-12.0.0_r34/ojluni/src/main/java/java/util/Base64.java).

## Disk-backed large-file benchmark

Command:

```sh
./gradlew :core:test :core:largeFileBenchmark --console=plain
```

This opt-in task generates deterministic source files on disk, encodes and decodes
with the production core, then reopens source/recovered files and compares
streaming SHA-256 digests. It does not keep file contents in memory. The JVM heap
limit is 256 MiB and its fixed I/O buffer is 64 KiB. A 50 ms sampler records the
maximum observed used Java heap while conversion runs. Large temporary source,
encoded and recovered files are removed after each successful comparison.

Result: **BUILD SUCCESSFUL**. Runtime: OpenJDK **21.0.12.1**, Linux **amd64**, 4 available processors. Both sizes ran sequentially in the same JVM with a 256 MiB maximum heap. The combined unit-test and benchmark Gradle command took 45 seconds, including compilation and file generation/hashing.

| Source size | Encode | Decode | Peak sampled used heap | Heap committed after conversion |
| --- | ---: | ---: | ---: | ---: |
| 16 MiB (16,777,216 bytes) | 0.067 s | 0.241 s | 3,212,512 bytes (3.06 MiB) | 17,825,792 bytes |
| 1 GiB (1,073,741,824 bytes) | 1.947 s | 21.048 s | 2,359,528 bytes (2.25 MiB) | 8,388,608 bytes |

The 64× larger source did not produce linear heap growth. The larger run's lower sampled heap reflects JVM collection/commit behavior and sampling; it does not imply that larger files inherently require less memory. The 50 ms samples may miss very short-lived peaks. The real files and SHA-256 checks provide a full 1 GiB round-trip acceptance result on this host.

SHA-256 (source and recovered match in each row):

```text
16 MiB: 9dbcf559fc3387a096b2f849b3949fec0d4bdf2af230af33e7b9a5032f1fef33
 1 GiB: dca252499f6bfa02d9d16ecaf7e8efc17aab184afe155607bfae22c7d698a949
```

Raw command output is retained as `docs/verification/core-large-file-benchmark.log`, and the original JUnit XML as `docs/verification/core-unit-tests.xml`.

## Scope / 测试范围

These results verify the pure JVM codec, not Android UI performance or every
Storage Access Framework provider. Heap samples are process measurements for
this Linux/JVM configuration; they are not Android RSS, a universal memory bound,
or a throughput promise. Android/storage integration results are reported
separately in the project delivery documentation.

正文空白开关关闭时，包装正文中的换行也被拒绝，包括正文与 END 行之间的换行；
读取 certutil 风格的非空包装文件需要同时开启“识别 CERTIFICATE 包装”和“忽略 ASCII 空白”。
