package net.fabricmc.filament.task;

import java.io.IOException;
import java.nio.file.Files;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/// Packages the JDK module classes into a JAR readable by ordinary classpath consumers.
public abstract class JavaRuntimeJarTask extends DefaultTask {
	@InputDirectory
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract DirectoryProperty getModules();

	@OutputFile
	public abstract RegularFileProperty getOutput();

	@TaskAction
	public void run() throws IOException {
		var output = getOutput().get().getAsFile().toPath();
		Files.createDirectories(output.getParent());

		try (var modules = Files.list(getModules().get().getAsFile().toPath());
				var jar = new JarOutputStream(Files.newOutputStream(output))) {
			for (var module : modules.filter(path -> path.toString().endsWith(".jmod")).sorted().toList()) {
				try (var zip = new ZipFile(module.toFile())) {
					var entries = zip.entries();

					while (entries.hasMoreElements()) {
						var entry = entries.nextElement();
						String name = entry.getName();

						if (entry.isDirectory() || !name.startsWith("classes/") || !name.endsWith(".class")) {
							continue;
						}

						String className = name.substring("classes/".length());

						// The modules share this name, and the combined JAR is used as a classpath library.
						if (className.equals("module-info.class")) {
							continue;
						}

						var jarEntry = new JarEntry(className);
						jarEntry.setTime(0);
						jar.putNextEntry(jarEntry);

						try (var input = zip.getInputStream(entry)) {
							input.transferTo(jar);
						}

						jar.closeEntry();
					}
				}
			}
		}
	}
}
