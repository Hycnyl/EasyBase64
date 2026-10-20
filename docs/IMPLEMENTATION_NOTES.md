# Implementation decisions

The supplied design is preserved verbatim in `DEVELOPMENT_DESIGN.md`.

- Kotlin/JVM core, Android data module, and Compose app remain separate modules. Android uses the stable AGP built-in Kotlin integration, with Kotlin/Compose compiler versions aligned explicitly.
- `BatchRepository` is application-scoped and exposes StateFlow. The service owns execution and activity recreation observes the existing repository. This small application uses manual dependency wiring rather than a DI framework.
- Room stores normalized header, file, attempt and directory rows in a transaction. Each record's scalar metadata uses JSON, but the complete batch is never a single database row. This avoids the Android CursorWindow size limit for large queues. No file bodies enter the database.
- A few UI preferences (language, theme, codec draft and recursive flag) use SharedPreferences. This is a deliberate simplification of the design's suggested DataStore preference layer. It does not affect the Room execution journal or immutable batch snapshots.
- The UI uses Material 3 dynamic color and Adaptive 1.3 window/posture information. It chooses a list/detail layout from the actual usable width and height, and constrains content away from separating/occluding hinges. It does not use device-name/orientation heuristics.
- Compile SDK is the stable 37.2 SDK available from Google's repository at build time. Target SDK is 36, with typed dataSync foreground service, runtime notification consent and timeout handling. Min SDK is 31.
- A private delivery signing key was generated because the user explicitly requested an installable APK and signing material. It is separate from Git and no app-store/public release or remote repository was created.
- The release has no ABI splits. The single universal APK contains the runtime libraries shipped by its AndroidX dependencies for the supported architectures.

Provider-specific behavior and actual verification scope are documented in `TEST_RESULTS.md` and `STORAGE_NOTES.md`. The JVM benchmark measures the codec in the build environment, not Android device throughput or overall Android process memory.
