package net.fabricmc.filament.task;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.filament.util.Mappings;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/// A step of the pipeline fixing the mappings, writing the fixed mappings formatted to the output directory.
public abstract class FixMappingsTask extends DefaultTask {
	@InputDirectory
	public abstract DirectoryProperty getMappings();

	@OutputDirectory
	public abstract DirectoryProperty getOutput();

	@Inject
	protected abstract FileSystemOperations getFileSystemOperations();

	@TaskAction
	public void run() throws IOException {
		MemoryMappingTree tree = fix(getMappings().get().getAsFile().toPath());
		getFileSystemOperations().delete(spec -> spec.delete(getOutput()));
		Mappings.write(tree, getOutput().get().getAsFile().toPath());
	}

	/// Reads the mappings and returns them with the invalid mappings dropped.
	protected abstract MemoryMappingTree fix(Path mappings) throws IOException;

	/// Drops the javadocs of the elements with errors, keyed by [Mappings#key(MappingTree.ElementMapping)].
	protected void dropJavadocs(MappingTree tree, Map<String, String> errors) {
		List<String> dropped = new ArrayList<>();

		for (MappingTree.ClassMapping cls : tree.getClasses()) {
			List<MappingTree.ElementMapping> elements = new ArrayList<>();
			elements.add(cls);
			elements.addAll(cls.getFields());
			elements.addAll(cls.getMethods());

			for (MappingTree.ElementMapping element : elements) {
				String key = Mappings.key(element);
				String error = errors.get(key);
				if (error == null) continue;

				// Only the javadoc is dropped, as members of a class may still have valid javadocs
				element.setComment(null);
				dropped.add(key + " " + error);
			}
		}

		logDropped(dropped);
	}

	protected void logDropped(Collection<String> reasons) {
		if (reasons.isEmpty()) return;

		getLogger().lifecycle("Dropped {} invalid mappings:\n\t{}", reasons.size(), String.join("\n\t", reasons));
	}
}
