package net.fabricmc.filament.task;

import java.io.IOException;
import java.nio.file.Path;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;

import net.fabricmc.filament.util.Mappings;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/// Drops the javadocs failing [CheckDecompileTask].
public abstract class DropDecompileErrorsTask extends FixMappingsTask {
	/// The [DecompileTask#getReport()] of the mappings.
	@InputFile
	public abstract RegularFileProperty getReport();

	@Override
	protected MemoryMappingTree fix(Path mappings) throws IOException {
		MemoryMappingTree tree = Mappings.read(mappings);
		dropJavadocs(tree, DecompileTask.readReport(getReport().get().getAsFile().toPath()));
		return tree;
	}
}
