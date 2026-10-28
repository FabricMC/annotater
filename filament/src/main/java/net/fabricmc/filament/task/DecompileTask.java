package net.fabricmc.filament.task;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.Manifest;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
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

import net.fabricmc.fernflower.api.FabricJavadocStyle;
import net.fabricmc.fernflower.api.IFabricJavadocProvider;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/// Decompiles the Minecraft jars with the javadocs inserted, and saves the sources of the documented classes so the
/// javadocs can be checked by the javadoc tool.
public abstract class DecompileTask extends DefaultTask {
	@InputDirectory
	public abstract DirectoryProperty getMappings();

	@InputFiles
	public abstract ConfigurableFileCollection getMinecraftJars();

	@CompileClasspath
	public abstract ConfigurableFileCollection getLibraries();

	@OutputDirectory
	public abstract DirectoryProperty getOutput();

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
		});
	}

	public interface Parameters extends WorkParameters {
		DirectoryProperty getMappings();

		ConfigurableFileCollection getMinecraftJars();

		ConfigurableFileCollection getLibraries();

		DirectoryProperty getOutput();
	}

	public abstract static class Decompile implements WorkAction<Parameters> {
		@Override
		public void execute() {
			MemoryMappingTree tree = new MemoryMappingTree();

			try {
				MappingReader.read(getParameters().getMappings().get().getAsFile().toPath(), MappingFormat.ENIGMA_DIR, tree);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			// The top level classes containing javadocs, the nested classes are in the same file
			Set<String> documented = new HashSet<>();
			// The elements with javadocs, the index is used to identify them in the decompiled source
			List<MappingTree.ElementMapping> javadocs = new ArrayList<>();

			for (MappingTree.ClassMapping cls : tree.getClasses()) {
				documented.add(cls.getSrcName().split("\\$")[0]);
				javadocs.add(cls);
				javadocs.addAll(cls.getFields());
				javadocs.addAll(cls.getMethods());
			}

			javadocs.removeIf(element -> element.getComment() == null);
			Map<MappingTree.ElementMapping, Integer> ids = new HashMap<>();

			for (int i = 0; i < javadocs.size(); i++) {
				if (javadocs.get(i).getComment().contains(MARKER)) {
					throw new GradleException("Javadoc contains the marker \"" + MARKER + "\": " + javadocs.get(i).getComment());
				}

				ids.put(javadocs.get(i), i);
			}

			Map<String, Object> options = new HashMap<>();
			options.put(IFabricJavadocProvider.PROPERTY_NAME, new JavadocProvider(tree, ids));
			options.put(IFernflowerPreferences.THREADS, String.valueOf(Runtime.getRuntime().availableProcessors()));

			Saver saver = new Saver(getParameters().getOutput().get().getAsFile().toPath(), documented, ConcurrentHashMap.newKeySet());
			GradleLogger logger = new GradleLogger(Logging.getLogger(DecompileTask.class));
			Fernflower fernflower = new Fernflower(saver, options, logger);
			getParameters().getMinecraftJars().forEach(fernflower::addSource);
			getParameters().getLibraries().forEach(fernflower::addLibrary);

			try {
				fernflower.decompileContext();
			} finally {
				fernflower.clearContext();
			}

			// A javadoc missing from the source is on a member hidden by the decompiler, such as a synthetic method or record accessor
			List<String> unused = new ArrayList<>();

			for (int i = 0; i < javadocs.size(); i++) {
				if (saver.written().contains(i)) continue;

				unused.add(switch (javadocs.get(i)) {
				case MappingTree.ClassMapping cls -> cls.getSrcName();
				case MappingTree.FieldMapping field -> field.getOwner().getSrcName() + "." + field.getSrcName() + ":" + field.getSrcDesc();
				case MappingTree.MethodMapping method -> method.getOwner().getSrcName() + "." + method.getSrcName() + method.getSrcDesc();
				default -> throw new IllegalStateException("Unexpected mapping type: " + javadocs.get(i).getClass());
				});
			}

			if (!unused.isEmpty()) {
				throw new GradleException("Javadocs not in the decompiled source:\n\t" + String.join("\n\t", unused));
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
			if (mapping == null || mapping.getComment() == null) return null;

			return MARKER + ids.get(mapping) + "\n" + mapping.getComment();
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

	/// Saves the sources of the documented classes with the markers removed, recording the ids of the javadocs written.
	private record Saver(Path output, Set<String> documented, Set<Integer> written) implements IResultSaver {
		@Override
		public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, @Nullable String content) {
			if (content == null || !documented.contains(qualifiedName)) return;

			// Strip the marker, recording that the marker was seen
			Matcher matcher = MARKER_LINE.matcher(content);
			content = matcher.replaceAll(result -> {
				written.add(Integer.parseInt(result.group(1)));
				return "";
			});

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
