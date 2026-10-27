package net.fabricmc.filament.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.TaskAction;
import org.jspecify.annotations.Nullable;

import net.fabricmc.filament.util.ClassPath;
import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.adapter.EmptyElementFilter;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.mappingio.tree.VisitOrder;

/// Checks that the mappings only contain javadocs, have no duplicates, are formatted
/// and only reference classes and members that exist in the Minecraft jars.
public abstract class CheckMappingsTask extends DefaultTask {
	@InputDirectory
	public abstract DirectoryProperty getMappings();

	@InputFiles
	public abstract ConfigurableFileCollection getMinecraftJars();

	@TaskAction
	public void run() throws IOException {
		Path mappings = getMappings().get().getAsFile().toPath();
		Set<String> errors = new LinkedHashSet<>();

		try (ClassPath minecraft = new ClassPath(getMinecraftJars())) {
			MappingReader.read(mappings, MappingFormat.ENIGMA_DIR, new Validator(minecraft, errors));
		}

		Path formatted = getTemporaryDir().toPath();
		CheckMappingsTask.format(mappings, formatted);
		Map<String, String> expected = readMappingFiles(formatted);
		Map<String, String> actual = readMappingFiles(mappings);
		Set<String> files = new HashSet<>(expected.keySet());
		files.addAll(actual.keySet());

		for (String file : files) {
			if (!Objects.equals(expected.get(file), actual.get(file))) {
				errors.add(file + " is not formatted correctly");
			}
		}

		if (!errors.isEmpty()) {
			errors.forEach(getLogger()::error);
			throw new GradleException("Found " + errors.size() + " mapping errors, see the log for details");
		}
	}

	static void format(Path input, Path output) throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();
		MappingReader.read(input, MappingFormat.ENIGMA_DIR, tree);
	
		try (MappingWriter writer = MappingWriter.create(output, MappingFormat.ENIGMA_DIR)) {
			tree.accept(new EmptyElementFilter(writer), VisitOrder.createByName());
		}
	}

	private static Map<String, String> readMappingFiles(Path dir) throws IOException {
		Map<String, String> files = new HashMap<>();

		try (Stream<Path> stream = Files.walk(dir)) {
			for (Path file : (Iterable<Path>) stream.filter(p -> p.toString().endsWith(".mapping"))::iterator) {
				files.put(dir.relativize(file).toString(), Files.readString(file));
			}
		}

		return files;
	}

	private static final class Validator implements MappingVisitor {
		private final ClassPath minecraft;
		private final Set<String> errors;
		private final Set<String> visited = new HashSet<>();
		private @Nullable String className;
		private @Nullable Set<String> members;
		// The element currently being visited, used for error messages
		private @Nullable String element;

		private Validator(ClassPath minecraft, Set<String> errors) {
			this.minecraft = minecraft;
			this.errors = errors;
		}

		@Override
		public void visitNamespaces(String srcNamespace, List<String> dstNamespaces) {
		}

		@Override
		public boolean visitClass(String srcName) {
			// Classes may be visited more than once (e.g. after a nested class), so duplicates are checked by their comment
			className = element = srcName;
			members = minecraft.getMembers(srcName);

			if (members == null) {
				errors.add(element + " does not exist");
				return false;
			}

			return true;
		}

		@Override
		public boolean visitField(String srcName, @Nullable String srcDesc) {
			return visitMember(srcName + ":" + srcDesc);
		}

		@Override
		public boolean visitMethod(String srcName, @Nullable String srcDesc) {
			return visitMember(srcName + srcDesc);
		}

		private boolean visitMember(String key) {
			element = className + "." + key;

			if (!visited.add(element)) {
				errors.add(element + " is duplicated");
			}

			if (!members.contains(key)) {
				errors.add(element + " does not exist");
				return false;
			}

			return true;
		}

		@Override
		public boolean visitMethodArg(int argPosition, int lvIndex, @Nullable String srcName) {
			// Loom requires parameter javadocs to have a name, document parameters with @param in the method javadoc instead
			errors.add(element + " has a parameter mapping, use @param in the method javadoc instead");
			return false;
		}

		@Override
		public boolean visitMethodVar(int lvtRowIndex, int lvIndex, int startOpIdx, int endOpIdx, @Nullable String srcName) {
			return false;
		}

		@Override
		public void visitDstName(MappedElementKind targetKind, int namespace, String name) {
			errors.add(element + " is mapped to " + name + ", only javadocs are allowed");
		}

		@Override
		public void visitComment(MappedElementKind targetKind, String comment) {
			if (targetKind == MappedElementKind.CLASS && !visited.add(element)) {
				errors.add(element + " has duplicate javadocs");
			}
		}
	}
}
