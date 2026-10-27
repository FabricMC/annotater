package net.fabricmc.filament.task;

import java.io.IOException;
import java.util.List;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.mappingio.tree.VisitOrder;

/// Converts the mappings to a tiny v2 file.
public abstract class ConvertMappingsTask extends DefaultTask {
	@InputDirectory
	public abstract DirectoryProperty getMappings();

	@OutputFile
	public abstract RegularFileProperty getOutput();

	@TaskAction
	public void run() throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();
		MappingReader.read(getMappings().get().getAsFile().toPath(), MappingFormat.ENIGMA_DIR, tree);
		tree.setSrcNamespace("official");
		tree.setDstNamespaces(List.of());
		tree.visitMetadata("markdown", null);

		try (MappingWriter writer = MappingWriter.create(getOutput().get().getAsFile().toPath(), MappingFormat.TINY_2_FILE)) {
			tree.accept(writer, VisitOrder.createByName());
		}
	}
}
