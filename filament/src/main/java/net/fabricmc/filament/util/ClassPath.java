package net.fabricmc.filament.util;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/// Lazily reads the members of classes by their internal name from a set of jars.
public final class ClassPath implements Closeable {
	private final List<ZipFile> jars = new ArrayList<>();
	private final Map<String, Optional<Set<String>>> cache = new HashMap<>();

	public ClassPath(Iterable<File> files) throws IOException {
		for (File file : files) {
			jars.add(new ZipFile(file));
		}
	}

	/// Returns the members of the class, "name:desc" for fields and "namedesc" for methods, or null if it does not exist.
	public @Nullable Set<String> getMembers(String name) {
		return cache.computeIfAbsent(name, k -> {
			try {
				return Optional.ofNullable(read(name + ".class"));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}).orElse(null);
	}

	private @Nullable Set<String> read(String file) throws IOException {
		for (ZipFile jar : jars) {
			ZipEntry entry = jar.getEntry(file);
			if (entry == null) continue;

			try (InputStream is = jar.getInputStream(entry)) {
				Set<String> members = new HashSet<>();
				new ClassReader(is).accept(new ClassVisitor(Opcodes.ASM9) {
					@Override
					public @Nullable FieldVisitor visitField(int access, String name, String descriptor, @Nullable String signature, @Nullable Object value) {
						members.add(name + ":" + descriptor);
						return null;
					}

					@Override
					public @Nullable MethodVisitor visitMethod(int access, String name, String descriptor, @Nullable String signature, String @Nullable [] exceptions) {
						members.add(name + descriptor);
						return null;
					}
				}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				return members;
			}
		}

		return null;
	}

	@Override
	public void close() throws IOException {
		for (ZipFile jar : jars) {
			jar.close();
		}
	}
}
