package net.fabricmc.filament.task;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.InputFiles;

import net.fabricmc.filament.util.ClassPath;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/// Drops the mappings failing [CheckMappingsTask]. Only one of each duplicated element is kept, and the formatting is
/// fixed by writing the mappings.
public abstract class DropCheckMappingsErrorsTask extends FixMappingsTask {
	@InputFiles
	public abstract ConfigurableFileCollection getMinecraftJars();

	@Override
	protected MemoryMappingTree fix(Path mappings) throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();
		Set<String> errors = new LinkedHashSet<>();

		try (ClassPath minecraft = new ClassPath(getMinecraftJars())) {
			MappingReader.read(mappings, MappingFormat.ENIGMA_DIR, new CheckMappingsTask.Validator(tree, minecraft, errors));
		}

		logDropped(errors);
		return tree;
	}
}
