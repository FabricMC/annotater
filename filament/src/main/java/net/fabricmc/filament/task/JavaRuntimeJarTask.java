package net.fabricmc.filament.task;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

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
	public abstract DirectoryProperty getJavaHome();

	@OutputFile
	public abstract RegularFileProperty getOutput();

	@TaskAction
	public void run() throws IOException {
		var output = getOutput().get().getAsFile().toPath();
		var javaHome = getJavaHome().get().getAsFile();
		Files.createDirectories(output.getParent());

		try (var runtime = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", javaHome.toString()));
				var modules = Files.list(runtime.getPath("/modules"));
				var jar = new JarOutputStream(Files.newOutputStream(output))) {
			for (var module : modules.sorted().toList()) {
				try (var files = Files.walk(module)) {
					var classFiles = files.filter(path -> path.toString().endsWith(".class"))
							.filter(Files::isRegularFile).sorted().toList();

					for (var classFile : classFiles) {
						String className = module.relativize(classFile).toString();

						// The modules share this name, and the combined JAR is used as a classpath library.
						if (className.equals("module-info.class")) {
							continue;
						}

						var jarEntry = new JarEntry(className);
						jarEntry.setTime(0);
						jar.putNextEntry(jarEntry);
						Files.copy(classFile, jar);
						jar.closeEntry();
					}
				}
			}
		}
	}
}
