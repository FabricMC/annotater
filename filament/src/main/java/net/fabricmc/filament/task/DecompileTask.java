package net.fabricmc.filament.task;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkerExecutor;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructField;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jspecify.annotations.Nullable;

import net.fabricmc.filament.util.Mappings;
import net.fabricmc.fernflower.api.FabricJavadocStyle;
import net.fabricmc.fernflower.api.IFabricJavadocProvider;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/// Decompiles the Minecraft jars with the javadocs inserted, and saves the sources of the documented classes so the
/// javadocs can be checked by the javadoc tool. The invalid javadocs are reported instead of failing, so they can be
/// checked by [CheckDecompileTask] or dropped by [DropDecompileErrorsTask] without decompiling again.
public abstract class DecompileTask extends DefaultTask {
	@InputDirectory
	public abstract DirectoryProperty getMappings();

	@InputFiles
	public abstract ConfigurableFileCollection getMinecraftJars();

	@CompileClasspath
	public abstract ConfigurableFileCollection getLibraries();

	@OutputDirectory
	public abstract DirectoryProperty getOutput();

	/// The invalid javadocs, one "element\treason" per line.
	@OutputFile
	public abstract RegularFileProperty getReport();

	/// Where the javadocs are in the decompiled sources, see [JavadocLines].
	@OutputFile
	public abstract RegularFileProperty getJavadocLines();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	protected abstract FileSystemOperations getFileSystemOperations();

	@TaskAction
	public void run() {
		getFileSystemOperations().delete(spec -> spec.delete(getOutput()));
		// Run in a separate process to decompile with more memory
		getWorkerExecutor().processIsolation(spec -> spec.getForkOptions().setMaxHeapSize("4G")).submit(Decompile.class, parameters -> {
			parameters.getMappings().set(getMappings());
			parameters.getMinecraftJars().from(getMinecraftJars());
			parameters.getLibraries().from(getLibraries());
			parameters.getOutput().set(getOutput());
			parameters.getReport().set(getReport());
			parameters.getJavadocLines().set(getJavadocLines());
		});
	}

	/// Reads a [#getReport()], mapping the invalid elements to the reasons they are invalid.
	public static Map<String, String> readReport(Path report) throws IOException {
		Map<String, String> errors = new LinkedHashMap<>();

		for (String line : Files.readAllLines(report)) {
			String[] parts = line.split("\t", 2);
			errors.put(parts[0], parts[1]);
		}

		return errors;
	}

	public interface Parameters extends WorkParameters {
		DirectoryProperty getMappings();

		ConfigurableFileCollection getMinecraftJars();

		ConfigurableFileCollection getLibraries();

		DirectoryProperty getOutput();

		RegularFileProperty getReport();

		RegularFileProperty getJavadocLines();
	}

	/// The lines from `start` to `end` (inclusive) of the decompiled source `file` are the javadoc of `element`.
	public record JavadocLines(String file, int start, int end, String element) {
		public static List<JavadocLines> read(Path path) throws IOException {
			return Files.readAllLines(path).stream().map(line -> {
				String[] parts = line.split("\t");
				return new JavadocLines(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), parts[3]);
			}).toList();
		}

		private String write() {
			return file + "\t" + start + "\t" + end + "\t" + element;
		}
	}

	public abstract static class Decompile implements WorkAction<Parameters> {
		@Override
		public void execute() {
			MemoryMappingTree tree;

			try {
				tree = Mappings.read(getParameters().getMappings().get().getAsFile().toPath());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			// The top level classes containing javadocs, the nested classes are in the same file
			Set<String> documented = new HashSet<>();
			// The elements with javadocs, the index is used to identify them in the decompiled source
			List<MappingTree.ElementMapping> javadocs = new ArrayList<>();
			// The invalid javadocs, as "element\treason"
			List<String> errors = new ArrayList<>();

			for (MappingTree.ClassMapping cls : tree.getClasses()) {
				documented.add(cls.getSrcName().split("\\$")[0]);
				javadocs.add(cls);
				javadocs.addAll(cls.getFields());
				javadocs.addAll(cls.getMethods());
			}

			javadocs.removeIf(element -> element.getComment() == null);
			javadocs.removeIf(element -> {
				if (!element.getComment().contains(MARKER)) return false;

				errors.add(Mappings.key(element) + "\thas a javadoc containing the reserved marker \"" + MARKER + "\"");
				return true;
			});
			Map<MappingTree.ElementMapping, Integer> ids = new HashMap<>();

			for (int i = 0; i < javadocs.size(); i++) {
				ids.put(javadocs.get(i), i);
			}

			Map<String, Object> options = new HashMap<>();
			options.put(IFabricJavadocProvider.PROPERTY_NAME, new JavadocProvider(tree, ids));
			options.put(IFernflowerPreferences.THREADS, String.valueOf(Runtime.getRuntime().availableProcessors()));

			Saver saver = new Saver(getParameters().getOutput().get().getAsFile().toPath(), documented, new ConcurrentHashMap<>());
			GradleLogger logger = new GradleLogger(Logging.getLogger(DecompileTask.class));
			Fernflower fernflower = new Fernflower(saver, options, logger);
			getParameters().getMinecraftJars().forEach(fernflower::addSource);
			getParameters().getLibraries().forEach(fernflower::addLibrary);

			try {
				fernflower.decompileContext();
			} finally {
				fernflower.clearContext();
			}

			List<String> lines = new ArrayList<>();

			for (int i = 0; i < javadocs.size(); i++) {
				MappingTree.ElementMapping element = javadocs.get(i);
				Location location = saver.written().get(i);

				if (location == null) {
					// A javadoc missing from the source is on a member hidden by the decompiler, such as a synthetic method or record accessor
					errors.add(Mappings.key(element) + "\thas a javadoc that is not in the decompiled source");
					continue;
				}

				int lineCount = element.getComment().split("\\R", -1).length;
				lines.add(new JavadocLines(location.file(), location.line(), location.line() + lineCount - 1, Mappings.key(element)).write());
			}

			try {
				Files.write(getParameters().getReport().get().getAsFile().toPath(), errors);
				Files.write(getParameters().getJavadocLines().get().getAsFile().toPath(), lines);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}

	private static final class GradleLogger extends IFernflowerLogger {
		private final Logger logger;

		GradleLogger(Logger logger) {
			this.logger = logger;
		}

		private static LogLevel level(Severity severity) {
			return switch (severity) {
			case TRACE -> LogLevel.DEBUG;
			case INFO -> LogLevel.INFO;
			case WARN -> LogLevel.WARN;
			case ERROR -> LogLevel.ERROR;
			};
		}

		@Override
		public boolean accepts(Severity severity) {
			return logger.isEnabled(level(severity));
		}

		@Override
		public void writeMessage(String message, Severity severity) {
			logger.log(level(severity), message);
		}

		@Override
		public void writeMessage(String message, Severity severity, Throwable t) {
			logger.log(level(severity), message, t);
		}
	}

	/// Marks the first line of each javadoc with its id, as the decompiler may discard a member after its javadoc was requested.
	private static final String MARKER = "annotater-javadoc:";
	private static final Pattern MARKER_LINE = Pattern.compile("^[ \\t]*/// " + MARKER + "(\\d+)\\R", Pattern.MULTILINE);

	private record JavadocProvider(MappingTree tree, Map<MappingTree.ElementMapping, Integer> ids) implements IFabricJavadocProvider {
		private @Nullable String getCommentWithMarker(MappingTree.@Nullable ElementMapping mapping) {
			// Javadocs without an id contain the marker, so are left out
			Integer id = mapping == null ? null : ids.get(mapping);
			if (id == null) return null;

			return MARKER + id + "\n" + mapping.getComment();
		}

		@Override
		public @Nullable String getClassDoc(StructClass cls) {
			return getCommentWithMarker(tree.getClass(cls.qualifiedName));
		}

		@Override
		public @Nullable String getFieldDoc(StructClass cls, StructField field) {
			return getCommentWithMarker(tree.getField(cls.qualifiedName, field.getName(), field.getDescriptor()));
		}

		@Override
		public @Nullable String getMethodDoc(StructClass cls, StructMethod method) {
			return getCommentWithMarker(tree.getMethod(cls.qualifiedName, method.getName(), method.getDescriptor()));
		}

		@Override
		public FabricJavadocStyle getClassJavadocStyle(StructClass cls) {
			return FabricJavadocStyle.MARKDOWN;
		}

		@Override
		public FabricJavadocStyle getFieldJavadocStyle(StructClass cls, StructField field) {
			return FabricJavadocStyle.MARKDOWN;
		}

		@Override
		public FabricJavadocStyle getMethodJavadocStyle(StructClass cls, StructMethod method) {
			return FabricJavadocStyle.MARKDOWN;
		}
	}

	/// The first line of a javadoc in a decompiled source file.
	private record Location(String file, int line) {
	}

	/// Saves the sources of the documented classes with the markers removed, recording where each javadoc was written by its id.
	private record Saver(Path output, Set<String> documented, Map<Integer, Location> written) implements IResultSaver {
		@Override
		public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, @Nullable String content) {
			if (content == null || !documented.contains(qualifiedName)) return;

			// Strip the markers, recording the line each javadoc starts on in the stripped source
			Matcher matcher = MARKER_LINE.matcher(content);
			StringBuilder stripped = new StringBuilder();
			int line = 1;
			int end = 0;

			while (matcher.find()) {
				line += (int) content.substring(end, matcher.start()).chars().filter(c -> c == '\n').count();
				end = matcher.end();
				written.put(Integer.parseInt(matcher.group(1)), new Location(entryName, line));
				matcher.appendReplacement(stripped, "");
			}

			matcher.appendTail(stripped);
			content = stripped.toString();

			try {
				Path file = output.resolve(entryName);
				Files.createDirectories(file.getParent());
				Files.writeString(file, content);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		@Override
		public void saveFolder(String path) {
		}

		@Override
		public void copyFile(String source, String path, String entryName) {
		}

		@Override
		public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
		}

		@Override
		public void createArchive(String path, String archiveName, Manifest manifest) {
		}

		@Override
		public void saveDirEntry(String path, String archiveName, String entryName) {
		}

		@Override
		public void copyEntry(String source, String path, String archiveName, String entry) {
		}

		@Override
		public void closeArchive(String path, String archiveName) {
		}
	}
}
