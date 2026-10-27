package net.fabricmc.filament.task;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
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

			for (MappingTree.ClassMapping cls : tree.getClasses()) {
				documented.add(cls.getSrcName().split("\\$")[0]);
			}

			Map<String, Object> options = new HashMap<>();
			options.put(IFabricJavadocProvider.PROPERTY_NAME, new JavadocProvider(tree));
			options.put(IFernflowerPreferences.THREADS, String.valueOf(Runtime.getRuntime().availableProcessors()));

			Saver saver = new Saver(getParameters().getOutput().get().getAsFile().toPath(), documented);
			GradleLogger logger = new GradleLogger(Logging.getLogger(DecompileTask.class));
			Fernflower fernflower = new Fernflower(saver, options, logger);
			getParameters().getMinecraftJars().forEach(fernflower::addSource);
			getParameters().getLibraries().forEach(fernflower::addLibrary);

			try {
				fernflower.decompileContext();
			} finally {
				fernflower.clearContext();
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

	private record JavadocProvider(MappingTree tree) implements IFabricJavadocProvider {
		@Override
		public @Nullable String getClassDoc(StructClass cls) {
			MappingTree.ClassMapping mapping = tree.getClass(cls.qualifiedName);
			return mapping != null ? mapping.getComment() : null;
		}

		@Override
		public @Nullable String getFieldDoc(StructClass cls, StructField field) {
			MappingTree.FieldMapping mapping = tree.getField(cls.qualifiedName, field.getName(), field.getDescriptor());
			return mapping != null ? mapping.getComment() : null;
		}

		@Override
		public @Nullable String getMethodDoc(StructClass cls, StructMethod method) {
			MappingTree.MethodMapping mapping = tree.getMethod(cls.qualifiedName, method.getName(), method.getDescriptor());
			return mapping != null ? mapping.getComment() : null;
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

	/// Saves the sources of the documented classes, and nothing else.
	private record Saver(Path output, Set<String> documented) implements IResultSaver {
		@Override
		public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, @Nullable String content) {
			if (content == null || !documented.contains(qualifiedName)) return;

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
