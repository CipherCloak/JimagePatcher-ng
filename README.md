# JImagePatcher-ng

Forked & refactored from https://github.com/CompassSecurity/JimagePatcher

This refactored code was made by AI.

---

The JDK tool `jimage` supports only the extraction of jimage files but not the recreation of them. If you need to patch code or resources in a jimage file (for example the `lib/modules` file of a JDK), you can use this tool to do so.

First extract the modules from the jimage file to a folder with the JDK tool:

    jimage extract --dir extracted-modules jimagefile

Then patch the files in the extracted folder as needed.

At last recreate the jimage file with this tool:

    java -jar jimagePatcher.jar extracted-modules new-jimagefile

## Requirements
- A JDK 11 or newer with the module `jdk.jlink`. Tested with JDK 11 to 27, see [Testing](#testing). Java 8 and older have no jimage files (they use `rt.jar`) and cannot run the tool.
- Run the tool with the same JDK version as the jimage file you patch. The tool prints a warning if the versions differ.
- If your JDK has no `jimage` launcher (e.g. the Ubuntu package `openjdk-25-jre-headless`), start `jimage` from its module:

      java -m jdk.jlink/jdk.tools.jimage.Main extract --dir extracted-modules jimagefile

## Build
    ./build.sh

creates `jimagePatcher.jar`, compiled for Java 11. The script uses the JDK in `$JAVA_HOME`, or else the `java` on the `PATH`. Without the script:

    javac --release 11 -d build/classes src/module-info.java src/jimagePatcher/Run.java
    jar --create --file jimagePatcher.jar --manifest src/META-INF/MANIFEST.MF --main-class jimagePatcher.Run -C build/classes .

## Usage
    java -jar jimagePatcher.jar [options] input-folder output-file

The manifest of the jar opens the internal jlink package to the tool (`Add-Opens`), so no further java options are needed. The command of the first version, which runs the tool from the module path, still works:

    java --add-opens jdk.jlink/jdk.tools.jlink.internal=jimagePatcher --module-path jimagePatcher.jar -m jimagePatcher/jimagePatcher.Run [options] input-folder output-file

| Option | Description |
|---|---|
| `-h`, `--help` | Print the usage |
| `--<plugin>[=<arguments>]` | Apply a jlink plugin, see [Plugins](#plugins) |

- Every sub folder of `input-folder` must be a module folder as created by `jimage extract`: named like the module and containing its `module-info.class`. Files and hidden folders directly in `input-folder` are ignored with a warning.
- The jimage file is first written to `output-file.tmp` and then moved to `output-file`. If anything fails, an existing `output-file` stays untouched.
- Exit codes: `0` success, `1` error, `2` invalid command line.

### Patching the lib/modules file of a JDK
    jimage extract --dir extracted-modules $JDK/lib/modules
    # ... patch the files in extracted-modules ...
    java -jar jimagePatcher.jar extracted-modules modules
    cp modules $JDK/lib/modules
    $JDK/bin/java -Xshare:dump

The last command regenerates the CDS archive (`lib/server/classes.jsa`) of the JDK. The CDS archive belongs to the original `lib/modules` file: with a new `lib/modules` file, Java starts without CDS (slower; newer JDKs print a warning, JDK 11 does not), with `-Xshare:on` it does not start at all.

Prefer patching a copy of the JDK over patching the JDK that runs the tool. On Windows, a `lib/modules` file in use cannot be replaced.

### Patching module-info.class
A JDK does not read the `module-info.class` files at startup. jlink compiles the module descriptors into the `SystemModules` classes of `java.base`, and these are used instead. A patched `module-info.class` (e.g. with an additional `exports`) therefore has no effect, unless the `SystemModules` classes are regenerated with the `--system-modules` plugin:

    java -jar jimagePatcher.jar --system-modules extracted-modules new-jimagefile

## Plugins
The tool supports the jlink plugins with the same option syntax as `jlink`, e.g. `--compress=2` or `--include-locales=en,de`. `jlink --list-plugins` lists the plugins of a JDK and their arguments. Differences to `jlink`:
- Arguments must be given with `=`: `--compress=2`, not `--compress 2`.
- The short options (`-c`, `-G`) and `--plugin-module-path` are not supported.
- No plugin is enabled by default. `jlink` enables some plugins automatically (e.g. `--system-modules`); this tool only applies the plugins given on the command line.

The tool only writes the jimage file, so only the plugins that change the content of the jimage have an effect:

| Plugin | Effect with this tool |
|---|---|
| `--compress` | Compresses the resources. JDK 11-20: `0`, `1`, `2`. JDK 21+: `zip-0` to `zip-9`, and the deprecated `0`, `1`, `2` |
| `--strip-debug` | Removes the debug information from the classes |
| `--strip-java-debug-attributes` | Removes the debug information from the classes (not in JDK 11) |
| `--include-locales`, `--exclude-resources`, `--order-resources` | Like with `jlink` |
| `--system-modules` | Regenerates the module descriptors, needed after patching `module-info.class` |
| `--add-options`, `--vendor-version`, `--vendor-bug-url`, `--vendor-vm-bug-url` | Like with `jlink` |
| `--dedup-legal-notices`, `--exclude-files`, `--exclude-jmod-section`, `--generate-cds-archive`, `--release-info`, `--strip-native-commands`, `--strip-native-debug-symbols` | No effect, these plugins change files outside of the jimage (the tool prints a warning) |
| `--generate-jli-classes` | Fails, the classes it generates are already in the extracted jimage |
| `--vm` | Fails, it needs the VM libraries, which are not in the jimage |

## Testing
    test/run-tests.sh [jdk-home...]

tests the tool with the `lib/modules` file of every given JDK (default: `$JAVA_HOME`, or else the JDK of the `java` on the `PATH`). Build the jar with `./build.sh` first. For every JDK the script:
1. extracts `lib/modules` and patches it (adds a resource to `java.base` and changes one),
2. recreates the jimage with `java -jar` and from the module path, and checks that both create the same file,
3. extracts the new jimage again and compares it with the patched folder,
4. installs the new jimage in a copy of the JDK, regenerates CDS, starts it with `-Xshare:on` and reads the patched resource,
5. repeats 2-4 with `--compress` and `--strip-debug`,
6. checks the error handling (exit codes, no output file after a failure).

Last test run, on Linux x64 (all checks passed):

| JDK | Version |
|---|---|
| Eclipse Temurin | 11.0.32.1, 17.0.20.1, 21.0.12.1, 22.0.2, 23.0.2, 24.0.2, 26.0.2.1, 27 |
| Oracle JDK | 22.0.2 |
| Azul Zulu | 11.0.32.1, 25.0.4.1 |
| Ubuntu OpenJDK | 25.0.4.1 |

The results of the [Plugins](#plugins) table were checked with the same JDKs.

## Infos
Stitched together with the help of
http://hg.openjdk.java.net/jdk9/jdk9/jdk/rev/78a06bc11975
and
http://jar.fyicenter.com/3245_JDK_11_jdk_jlink_jmod-JLink_Tool.html

The current sources of the used jlink classes: https://github.com/openjdk/jdk/tree/master/src/jdk.jlink/share/classes/jdk/tools/jlink/internal

jlink is not exported by its module, so the tool uses reflection to access it. The reflection only works if the module `jdk.jlink` opens the package `jdk.tools.jlink.internal` to the tool: the jar manifest does that for `java -jar`, from the module path the runtime needs to be started with `--add-opens jdk.jlink/jdk.tools.jlink.internal=jimagePatcher`.

The used internal API is the same in JDK 11 to 27, with one exception: JDK 24 added the parameter `boolean generateRuntimeImage` to `ImageFileCreator.recreateJimage` ([JEP 493](https://openjdk.org/jeps/493)). The tool looks up `recreateJimage` by name and fills its parameters by type, so it works with both signatures. As an internal API, it can change in any future JDK release.

## TODO
### Testing
The tests were only run on Linux. Windows and macOS are not tested yet.
### Plugins
Plugins that are not part of the JDK (`--plugin-module-path` of `jlink`) are not supported.
