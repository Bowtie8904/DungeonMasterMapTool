# Development and build

| What | Details |
|------|---------|
| Operating system | Windows (primary target), macOS or Linux with JavaFX support |
| Java | **JDK 25 or newer** (the build uses release 25) |
| Build tool | [Apache Maven](https://maven.apache.org/) 3.8+ (or use the `.mvn` wrapper folder if present) |
| Displays | One screen is enough; a **second screen** is needed for the player view |
| Memory | 2 GB free RAM recommended; large maps (>4096 px) use a disk cache |
| Map source (optional) | `.dd2vtt` / `.uvtt` files, e.g. from Dungeon Alchemist, or your own images |

## Build and run

Windows builds made with `packaging\build-exe.bat` (including its console wrapper) and CI include
the nine editable Daggerheart catalogs in `app\dmmap-daggerheart`. Browser HTML, JavaScript/CSS,
desktop and browser condition icons, the application icon and desktop styles are bundled inside
`app\DungeonMasterMapTool.jar`. Keep the complete application folder, not just its EXE.
Each Windows build checks these resources against the source files before reporting success.
You can repeat the resource check with `powershell -NoProfile -ExecutionPolicy Bypass -File packaging\verify-package.ps1`
(use `-AppImage <folder>` for another app image). This checks package contents, not a live play session.

```powershell
# run from the source tree
mvn javafx:run

# or build a runnable "fat" jar and start it
mvn package
java -XX:+UseCompactObjectHeaders -jar target\DungeonMasterMapTool-1.0-SNAPSHOT-all.jar

# run the unit tests
mvn test

# run the separate Online Play browser tests
npm --prefix web test
```

Main class: `dmmt.DungeonMasterMapToolLauncher`.

Tests use temporary copies of `src/test/resources/dmmt/service/test-settings.ini`, never the project-root
settings files, in both Maven and IDE JUnit runs. Keep any additional settings fixtures in test resources.
Profiler accounting tests use deterministic timing rather than real-time waits.
Remote rejection tests check certificate pins and compatibility versions separately so TCP backlog contention cannot mask the intended rejection.
To regenerate the optional built-in defaults reference, explicitly run
`mvn test -Dtest=DefaultSettingsFileTest -Dupdate.default.settings=true`.

Data locations:

- **Map library:** `dmmap-projects` beside `dmmap-audio` by default (created on first use, git-ignored; configurable with `library.folder`).
- **Settings:** `dmmt-settings.ini` next to the jar (see [Settings reference](SETTINGS.md)); override with `-Ddmmt.settings=<path>`.
- **Image tile cache:** `%LOCALAPPDATA%\DungeonMasterMapTool\image-cache` (fallback `~/.dmmt/image-cache`); entries unused for 60 days are pruned.

## Technology stack

| Area | Technology |
|------|------------|
| Language / runtime | Java 25 (LTS) |
| UI and rendering | JavaFX 25 (Canvas rendering, custom dark CSS theme) |
| Icons | Ikonli with Material Design Icons 2 |
| Rich text editing | RichTextFX |
| Geometry / line of sight | JTS Topology Suite |
| JSON (dd2vtt import, `.dmmap` save format) | Jackson |
| Remote session certificate generation (internal) | Bouncy Castle PKIX; Java TLS 1.3 for connections |
| Boilerplate reduction | Lombok |
| Build | Maven (maven-shade-plugin for the fat jar, javafx-maven-plugin for `javafx:run`) |
| Tests | JUnit 5 |

Maps are saved in a versioned JSON format (`.dmmap`) that is identical for imported and custom maps, so every feature works on both.

---
