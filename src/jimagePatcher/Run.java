/*
 * JImagePatcher - recreates a jimage file (the lib/modules file of a Java runtime)
 * from a folder that was created with "jimage extract".
 *
 * How it works
 *   The JDK can extract a jimage ("jimage extract"), but it has no tool to write one
 *   back. The code that writes jimages is part of jlink, in the package
 *   jdk.tools.jlink.internal of the module jdk.jlink. That package is not exported,
 *   so this tool uses reflection to:
 *     1. wrap every module folder of the input folder in a DirArchive,
 *     2. create the requested jlink plugins with PluginRepository.newPlugin and
 *        combine them into an ImagePluginStack with
 *        ImagePluginConfiguration.parseConfiguration,
 *     3. write the jimage with ImageFileCreator.recreateJimage.
 *   The jimage is first written to "<output-file>.tmp" and then moved over the output
 *   file, so a failure never leaves a broken or half written output file behind.
 *
 * Access to the internal package
 *   The reflection only works if jdk.jlink opens jdk.tools.jlink.internal to this tool.
 *   "java -jar jimagePatcher.jar" does that through the Add-Opens attribute of the jar
 *   manifest. When the tool runs from the module path, the runtime has to be started
 *   with "--add-opens jdk.jlink/jdk.tools.jlink.internal=jimagePatcher".
 *
 * Compatibility
 *   Compiled for Java 11 and tested with JDK 11, 17, 21, 22, 23, 24, 25, 26 and 27.
 *   The internal API used here is the same in all these versions, with one exception:
 *   JDK 24 (JEP 493, "Linking Run-Time Images without JMOD Files") added the parameter
 *   "boolean generateRuntimeImage" to recreateJimage. recreateJimage is therefore looked
 *   up by name and its arguments are matched by type.
 *   Run the tool with the same JDK version as the image being patched.
 *
 * README.md describes the usage, the supported jlink plugins, the tests and the
 * limitations (CDS archive, patched module-info.class files).
 *
 * Based on
 *   https://github.com/CompassSecurity/JimagePatcher
 *   http://hg.openjdk.java.net/jdk9/jdk9/jdk/rev/78a06bc11975
 *   http://jar.fyicenter.com/3245_JDK_11_jdk_jlink_jmod-JLink_Tool.html
 *   https://github.com/openjdk/jdk/tree/master/src/jdk.jlink/share/classes/jdk/tools/jlink/internal
 */
package jimagePatcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Run {

	private static final String USAGE = String.join(System.lineSeparator(),
			"Usage: java -jar jimagePatcher.jar [options] <input-folder> <output-file>",
			"   or: java --add-opens jdk.jlink/jdk.tools.jlink.internal=jimagePatcher --module-path jimagePatcher.jar -m jimagePatcher/jimagePatcher.Run [options] <input-folder> <output-file>",
			"",
			"Creates the jimage <output-file> from <input-folder>, a folder created with \"jimage extract\".",
			"",
			"Options:",
			"  -h, --help                Print this help message",
			"  --<plugin>[=<arguments>]  Apply a jlink plugin, with the same syntax as jlink,",
			"                            e.g. --compress=2 or --strip-debug",
			"                            (\"jlink --list-plugins\" lists the plugins)");

	/** jlink plugins that only change files outside of the jimage, like the release file or the native libraries. */
	private static final Set<String> PLUGINS_WITHOUT_EFFECT = Set.of("dedup-legal-notices", "exclude-files", "exclude-jmod-section",
			"generate-cds-archive", "release-info", "strip-native-commands", "strip-native-debug-symbols");

	private static final int EXIT_ERROR = 1;
	private static final int EXIT_USAGE = 2;

	public static void main(String[] args) {
		try {
			run(args);
		} catch (UsageException e) {
			if (e.getMessage() != null) {
				System.err.println("Error: " + e.getMessage());
			}
			System.err.println(USAGE);
			System.exit(EXIT_USAGE);
		} catch (ToolException e) {
			System.err.println("Error: " + e.getMessage());
			if (e.getCause() != null) {
				e.getCause().printStackTrace();
			}
			System.exit(EXIT_ERROR);
		} catch (Exception e) {
			System.err.println("Error: " + e);
			e.printStackTrace();
			System.exit(EXIT_ERROR);
		}
	}

	private static void run(String[] args) throws Exception {
		List<String> files = new ArrayList<>();
		Map<String, String> pluginOptions = new LinkedHashMap<>();
		for (String arg : args) {
			if (arg.equals("-h") || arg.equals("--help")) {
				System.out.println(USAGE);
				return;
			} else if (arg.startsWith("--") && arg.length() > 2) {
				int eq = arg.indexOf('=');
				String name = eq < 0 ? arg.substring(2) : arg.substring(2, eq);
				if (pluginOptions.containsKey(name)) {
					throw new UsageException("Option --" + name + " is specified more than once");
				}
				pluginOptions.put(name, eq < 0 ? null : arg.substring(eq + 1));
			} else if (arg.startsWith("-") && arg.length() > 1) {
				throw new UsageException("Unknown option: " + arg);
			} else {
				files.add(arg);
			}
		}
		if (args.length == 0) {
			throw new UsageException(null);
		}
		if (files.size() != 2) {
			throw new UsageException("Expected <input-folder> and <output-file>, but got " + files.size() + " argument(s)");
		}

		Path input = Paths.get(files.get(0));
		if (!Files.isDirectory(input)) {
			throw new ToolException("Input folder not found: " + input);
		}
		input = input.toRealPath();
		Path output = Paths.get(files.get(1)).toAbsolutePath();
		if (Files.isDirectory(output)) {
			throw new ToolException("Output file is a folder: " + output);
		}
		if (!Files.isDirectory(output.getParent())) {
			throw new ToolException("Folder of the output file not found: " + output.getParent());
		}
		output = output.getParent().toRealPath().resolve(output.getFileName());
		if (output.startsWith(input)) {
			throw new ToolException("Output file must not be inside the input folder: " + output);
		}

		JlinkInternals jlink = new JlinkInternals();
		Object pluginStack = jlink.newPluginStack(pluginOptions);

		System.out.println("Adding following modules:");
		System.out.println("---");
		Set<Object> archives = new LinkedHashSet<>();
		for (Path module : findModules(input)) {
			System.out.println(module.getFileName());
			archives.add(jlink.newDirArchive(module));
		}
		System.out.println("---");

		System.out.println("Creating file " + output);
		Path tmp = output.resolveSibling(output.getFileName() + ".tmp");
		try {
			jlink.recreateJimage(tmp, archives, pluginStack);
			replace(tmp, output);
		} finally {
			Files.deleteIfExists(tmp);
		}
		System.out.println("Done");
		System.out.println("Note: if this file replaces <jdk>/lib/modules, regenerate the CDS archive of that JDK with \"<jdk>/bin/java -Xshare:dump\"");
	}

	/** Returns the module folders of the input folder, sorted by name so that the output is reproducible. */
	private static List<Path> findModules(Path input) throws IOException, ToolException {
		List<Path> children;
		try (Stream<Path> list = Files.list(input)) {
			children = list.sorted().collect(Collectors.toList());
		}
		List<Path> modules = new ArrayList<>();
		for (Path child : children) {
			if (!Files.isDirectory(child)) {
				System.err.println("Warning: ignoring file " + child);
			} else if (child.getFileName().toString().startsWith(".")) {
				System.err.println("Warning: ignoring hidden folder " + child);
			} else {
				checkModule(child);
				modules.add(child);
			}
		}
		if (modules.isEmpty()) {
			throw new ToolException("No module folders found in " + input);
		}
		return modules;
	}

	/** jlink takes the module name from the folder name, so the folder has to be named like the module in its module-info.class. */
	private static void checkModule(Path folder) throws IOException, ToolException {
		String name = folder.getFileName().toString();
		Path moduleInfo = folder.resolve("module-info.class");
		if (!Files.isRegularFile(moduleInfo)) {
			throw new ToolException("Folder " + folder + " is not a module, it has no module-info.class");
		}
		ModuleDescriptor descriptor;
		try (InputStream in = Files.newInputStream(moduleInfo)) {
			descriptor = ModuleDescriptor.read(in);
		} catch (InvalidModuleDescriptorException e) {
			// e.g. a module-info.class of a newer Java version than the running one
			System.err.println("Warning: cannot read " + moduleInfo + ": " + e.getMessage());
			return;
		}
		if (!descriptor.name().equals(name)) {
			throw new ToolException("Folder " + folder + " contains the module " + descriptor.name() + ", the folder must have the name of its module");
		}
		String version = descriptor.rawVersion().orElse(null);
		int runtimeFeature = Runtime.version().feature();
		if (name.equals("java.base") && version != null && !version.matches(runtimeFeature + "([.+-].*)?")) {
			System.err.println("Warning: the modules are from Java " + version + ", but this tool runs on Java " + Runtime.version()
					+ ". Run it with the same JDK version as the image to avoid an incompatible jimage.");
		}
	}

	/** Moves the finished jimage to its final place, atomically if the file system supports it. */
	private static void replace(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Converts the argument of a plugin option into the configuration map of the plugin,
	 * the same way jlink does: "--name=value:key1=value1:key2=value2" becomes
	 * {name=value, key1=value1, key2=value2}, "--name" becomes an empty map.
	 */
	private static Map<String, String> pluginConfiguration(String name, String argument) throws UsageException {
		Map<String, String> configuration = new HashMap<>();
		if (argument == null) {
			return configuration;
		}
		// ":" has to be followed by a word character, so Windows paths like C:\foo stay intact
		String[] parts = argument.split(":(?=\\w)", -1);
		if (parts[0].isEmpty()) {
			throw new UsageException("Option --" + name + " requires a value");
		}
		configuration.put(name, parts[0]);
		for (int i = 1; i < parts.length; i++) {
			int eq = parts[i].indexOf('=');
			if (eq <= 0 || eq == parts[i].length() - 1) {
				throw new UsageException("Invalid argument for --" + name + ": " + argument);
			}
			configuration.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
		}
		return configuration;
	}

	/** Reflective access to the internal jlink classes that read module folders and write jimages. */
	private static final class JlinkInternals {

		private static final String PACKAGE = "jdk.tools.jlink.internal";

		private final Class<?> dirArchive;
		private final Class<?> imageFileCreator;
		private final Class<?> imagePluginConfiguration;
		private final Class<?> imagePluginStack;
		private final Class<?> pluginRepository;
		private final Class<?> pluginsConfiguration;
		private final Class<?> utils;

		JlinkInternals() throws ToolException {
			try {
				dirArchive = Class.forName(PACKAGE + ".DirArchive");
				imageFileCreator = Class.forName(PACKAGE + ".ImageFileCreator");
				imagePluginConfiguration = Class.forName(PACKAGE + ".ImagePluginConfiguration");
				imagePluginStack = Class.forName(PACKAGE + ".ImagePluginStack");
				pluginRepository = Class.forName(PACKAGE + ".PluginRepository");
				pluginsConfiguration = Class.forName(PACKAGE + ".Jlink$PluginsConfiguration");
				utils = Class.forName(PACKAGE + ".Utils");
			} catch (ClassNotFoundException e) {
				throw new ToolException("The Java runtime " + System.getProperty("java.home")
						+ " does not contain the jlink internals (" + e.getMessage() + "), run the tool with a full JDK");
			}
			Module self = Run.class.getModule();
			if (!dirArchive.getModule().isExported(PACKAGE, self)) {
				String target = self.isNamed() ? self.getName() : "ALL-UNNAMED";
				throw new ToolException("No access to the package " + PACKAGE + ", run the tool with \"java -jar jimagePatcher.jar\""
						+ " or add \"--add-opens jdk.jlink/" + PACKAGE + "=" + target + "\" to the java command");
			}
		}

		Object newDirArchive(Path module) throws Exception {
			return dirArchive.getConstructor(Path.class, String.class).newInstance(module, module.getFileName().toString());
		}

		Object newPluginStack(Map<String, String> pluginOptions) throws Exception {
			Method newPlugin = pluginRepository.getMethod("newPlugin", Map.class, String.class, ModuleLayer.class);
			Method isDisabled = utils.getMethod("isDisabled", Class.forName("jdk.tools.jlink.plugin.Plugin"));
			List<Object> plugins = new ArrayList<>();
			for (Map.Entry<String, String> option : pluginOptions.entrySet()) {
				String name = option.getKey();
				Object plugin = newPlugin(newPlugin, name, pluginConfiguration(name, option.getValue()));
				if (plugin == null) {
					throw new UsageException("Unknown option: --" + name + " is not a jlink plugin of Java " + Runtime.version());
				}
				if (PLUGINS_WITHOUT_EFFECT.contains(name)) {
					System.err.println("Warning: --" + name + " has no effect, the plugin only changes files outside of the jimage");
				}
				// like jlink: a plugin can disable itself through its configuration, e.g. --compress=0
				if (!(Boolean) invoke(isDisabled, plugin)) {
					plugins.add(plugin);
				}
			}
			Object configuration = pluginsConfiguration.getConstructor(List.class).newInstance(plugins);
			return invoke(imagePluginConfiguration.getMethod("parseConfiguration", pluginsConfiguration), configuration);
		}

		/** PluginRepository.newPlugin prints a stack trace for an invalid configuration, the error message is enough here. */
		private static Object newPlugin(Method newPlugin, String name, Map<String, String> configuration) throws Exception {
			PrintStream err = System.err;
			ByteArrayOutputStream captured = new ByteArrayOutputStream();
			System.setErr(new PrintStream(captured, true));
			try {
				Object plugin = invoke(newPlugin, configuration, name, ModuleLayer.boot());
				err.print(captured);
				return plugin;
			} catch (IllegalArgumentException e) {
				throw new UsageException("Invalid argument for --" + name + ": " + e.getMessage());
			} catch (Exception e) {
				err.print(captured);
				throw e;
			} finally {
				System.setErr(err);
			}
		}

		void recreateJimage(Path output, Set<Object> archives, Object pluginStack) throws Exception {
			List<String> signatures = new ArrayList<>();
			for (Method method : imageFileCreator.getMethods()) {
				if (method.getName().equals("recreateJimage") && Modifier.isStatic(method.getModifiers())) {
					Object[] arguments = recreateJimageArguments(method.getParameterTypes(), output, archives, pluginStack);
					if (arguments != null) {
						try {
							invoke(method, arguments);
						} catch (Exception e) {
							throw new ToolException("Creating the jimage failed: " + e, e);
						}
						return;
					}
					signatures.add(method.toString());
				}
			}
			throw new ToolException("Java " + Runtime.version() + " is not supported, unknown signature of recreateJimage: " + signatures);
		}

		private Object[] recreateJimageArguments(Class<?>[] types, Path output, Set<Object> archives, Object pluginStack) {
			Object[] arguments = new Object[types.length];
			for (int i = 0; i < types.length; i++) {
				if (types[i] == Path.class) {
					arguments[i] = output;
				} else if (types[i] == Set.class) {
					arguments[i] = archives;
				} else if (types[i] == imagePluginStack) {
					arguments[i] = pluginStack;
				} else if (types[i] == boolean.class) {
					// JDK 24+: generateRuntimeImage, only true when jlink links from a run-time image instead of JMOD files
					arguments[i] = false;
				} else {
					return null;
				}
			}
			return arguments;
		}

		/** Invokes a static method and throws the exception of the method itself instead of an InvocationTargetException. */
		private static Object invoke(Method method, Object... arguments) throws Exception {
			try {
				return method.invoke(null, arguments);
			} catch (InvocationTargetException e) {
				if (e.getCause() instanceof Exception) {
					throw (Exception) e.getCause();
				}
				if (e.getCause() instanceof Error) {
					throw (Error) e.getCause();
				}
				throw e;
			}
		}
	}

	/** An error that is fully described by its message. */
	static class ToolException extends Exception {
		private static final long serialVersionUID = 1L;

		ToolException(String message) {
			super(message);
		}

		ToolException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/** An error in the command line, reported together with the usage. */
	static class UsageException extends ToolException {
		private static final long serialVersionUID = 1L;

		UsageException(String message) {
			super(message);
		}
	}
}
