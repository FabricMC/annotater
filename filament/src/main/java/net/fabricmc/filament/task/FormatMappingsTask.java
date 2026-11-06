package net.fabricmc.filament.task;

import java.io.IOException;
import java.nio.file.Path;

import net.fabricmc.filament.util.Mappings;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/// Formats the mappings, merging duplicated elements.
public abstract class FormatMappingsTask extends FixMappingsTask {
	@Override
	protected MemoryMappingTree fix(Path mappings) throws IOException {
		return Mappings.read(mappings);
	}
}
