# Repository Guidelines

## Project Structure & Module Organization

AtomChat is a Java Minecraft mod with three independent Gradle projects under `platforms/`: `1.21.1-fabric`, `1.21.1-neoforge`, and `1.20.1-forge`.

- `shared/src/main/java`: mapping-neutral logic shared by all targets.
- `layers/mapping/official/`: code shared by Forge and NeoForge; maintain matching Yarn implementations in Fabric.
- `platforms/<target>/src/main/`: platform code, metadata, and resources; assets live under `resources/assets/`.
- Tests follow the corresponding `src/test/java` and `src/test/resources` trees.
- `versions/` declares targets and layers; `tools/` contains validation scripts; `docs/` contains design and verification notes.

## Build, Test, and Development Commands

Use JDK 21 for Fabric/NeoForge and JDK 17 for Forge, plus Python 3 for repository guards. There is no root Gradle build. Run these inside a target directory, such as `platforms/1.21.1-fabric`:

```powershell
.\gradlew.bat build       # Compile, test, validate, and package
.\gradlew.bat test        # Run JUnit tests
.\gradlew.bat runClient   # Launch the development client
.\gradlew.bat atomchatLayers # Show mounted source layers
.\gradlew.bat build -Patomchat.nativePlatform=linux-x64
```

On Unix, use `./gradlew`. Default Windows JARs appear in `build/libs/`; Linux JARs in `build/linux-x64/libs/`. From the repository root, run `python tools/verify_targets.py` to validate the matrix and architecture.

## Coding Style & Naming Conventions

Follow adjacent Java code: four-space indentation, same-line opening braces, `PascalCase` classes, `camelCase` methods/fields, and `UPPER_SNAKE_CASE` constants. Keep shared code Java 17 compatible and mapping neutral. Use `UiTokens` for UI dimensions; `bareValueCheck`, included in `build`, rejects new unapproved `s(number)` literals. No standalone formatter or style linter is configured. Add UI translations to both `en_us.json` and `zh_cn.json` across affected targets.

## Testing Guidelines

Use JUnit 5, name test classes `*Test`, and place them beside the relevant source layer. Cover changed behavior and regressions; no numeric coverage threshold is configured. Build all three targets for shared changes. For rendering changes, use local `goldenCapture` before editing and `goldenCompare` afterward, then verify interactions in-game. Golden images remain untracked.

## Commit & Pull Request Guidelines

Prefer the prevalent history style: `feat:`, `fix:`, or `docs:`, with optional scope, e.g. `fix(ui): correct panel spacing`. Keep commits focused. PRs should describe behavior changes, link relevant issues, list tested targets/commands, and include screenshots for visible UI changes.

## Configuration & Packaging

Keep version and mod identity in root `gradle.properties`. Supply publishing tokens through environment variables. Distribute the OS-matching bundled JAR; Forge `-slim` JARs are development-only.
