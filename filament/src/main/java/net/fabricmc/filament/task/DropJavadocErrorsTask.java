package net.fabricmc.filament.task;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.DocumentationTool;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.jspecify.annotations.Nullable;

import net.fabricmc.filament.util.FilamentConstants;
import net.fabricmc.filament.util.Mappings;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/// Drops the javadocs failing the javadoc task, by running the javadoc tool on the sources decompiled by a
/// [DecompileTask] and finding the javadoc each error is in.
public abstract class DropJavadocErrorsTask extends FixMappingsTask {
	/// The [DecompileTask#getOutput()] of the mappings.
	@InputDirectory
	public abstract DirectoryProperty getSources();

	/// The [DecompileTask#getJavadocLines()] of the mappings.
	@InputFile
	public abstract RegularFileProperty getJavadocLines();

	@CompileClasspath
	public abstract ConfigurableFileCollection getClasspath();

	@Override
	protected MemoryMappingTree fix(Path mappings) throws IOException {
		Path sources = getSources().get().getAsFile().toPath();
		Map<String, List<DecompileTask.JavadocLines>> javadocs = DecompileTask.JavadocLines.read(getJavadocLines().get().getAsFile().toPath())
				.stream().collect(Collectors.groupingBy(DecompileTask.JavadocLines::file));
		// The first error in the javadoc of each element
		Map<String, String> errors = new LinkedHashMap<>();
		List<String> unknown = new ArrayList<>();

		DiagnosticListener<JavaFileObject> listener = diagnostic -> {
			if (diagnostic.getKind() != Diagnostic.Kind.ERROR) return;

			String element = findElement(sources, javadocs, diagnostic);

			if (element == null) {
				unknown.add(diagnostic.toString());
			} else {
				errors.putIfAbsent(element, "has an invalid javadoc: " + diagnostic.getMessage(Locale.ROOT));
			}
		};

		List<Path> files;

		try (Stream<Path> stream = Files.walk(sources)) {
			files = stream.filter(p -> p.toString().endsWith(".java")).toList();
		}

		if (!files.isEmpty()) {
			DocumentationTool tool = ToolProvider.getSystemDocumentationTool();

			try (StandardJavaFileManager fileManager = tool.getStandardFileManager(listener, Locale.ROOT, StandardCharsets.UTF_8)) {
				fileManager.setLocation(StandardLocation.CLASS_PATH, getClasspath().getFiles());
				fileManager.setLocationFromPaths(DocumentationTool.Location.DOCUMENTATION_OUTPUT, List.of(getTemporaryDir().toPath()));
				List<String> options = new ArrayList<>(FilamentConstants.JAVADOC_OPTIONS);
				// Report all errors, so they can all be dropped at once
				options.addAll(List.of("-Xmaxerrs", String.valueOf(Integer.MAX_VALUE)));
				tool.getTask(null, fileManager, listener, null, options, fileManager.getJavaFileObjectsFromPaths(files)).call();
			}
		}

		if (!unknown.isEmpty()) {
			getLogger().warn("Found {} javadoc errors outside of the javadocs, these must be fixed manually:\n{}", unknown.size(), String.join("\n", unknown));
		}

		MemoryMappingTree tree = Mappings.read(mappings);
		dropJavadocs(tree, errors);
		return tree;
	}

	private static @Nullable String findElement(Path sources, Map<String, List<DecompileTask.JavadocLines>> javadocs, Diagnostic<? extends JavaFileObject> diagnostic) {
		if (diagnostic.getSource() == null || diagnostic.getLineNumber() == Diagnostic.NOPOS) return null;

		String file = sources.relativize(Path.of(diagnostic.getSource().toUri())).toString().replace(File.separatorChar, '/');
		long line = diagnostic.getLineNumber();

		for (DecompileTask.JavadocLines javadoc : javadocs.getOrDefault(file, List.of())) {
			if (javadoc.start() <= line && line <= javadoc.end()) {
				return javadoc.element();
			}
		}

		return null;
	}
}
