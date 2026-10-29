package net.fabricmc.filament.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.adapter.EmptyElementFilter;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.mappingio.tree.VisitOrder;

/// Reads and writes the mappings, and identifies their elements in messages and reports.
public final class Mappings {
	private Mappings() {
	}

	public static MemoryMappingTree read(Path dir) throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();
		MappingReader.read(dir, MappingFormat.ENIGMA_DIR, tree);
		return tree;
	}

	/// Writes the mappings formatted, without any empty elements.
	public static void write(MappingTree tree, Path dir) throws IOException {
		Files.createDirectories(dir);

		try (MappingWriter writer = MappingWriter.create(dir, MappingFormat.ENIGMA_DIR)) {
			tree.accept(new EmptyElementFilter(writer), VisitOrder.createByName());
		}
	}

	public static String fieldKey(String owner, String name, @Nullable String desc) {
		return owner + "." + name + ":" + desc;
	}

	public static String methodKey(String owner, String name, @Nullable String desc) {
		return owner + "." + name + desc;
	}

	/// Returns the internal name for classes, "owner.name:desc" for fields and "owner.namedesc" for methods.
	public static String key(MappingTree.ElementMapping element) {
		return switch (element) {
		case MappingTree.ClassMapping cls -> cls.getSrcName();
		case MappingTree.FieldMapping field -> fieldKey(field.getOwner().getSrcName(), field.getSrcName(), field.getSrcDesc());
		case MappingTree.MethodMapping method -> methodKey(method.getOwner().getSrcName(), method.getSrcName(), method.getSrcDesc());
		default -> throw new IllegalStateException("Unexpected mapping type: " + element.getClass());
		};
	}
}
