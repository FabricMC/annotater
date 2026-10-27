package net.fabricmc.filament;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.external.javadoc.JavadocMemberLevel;
import org.gradle.external.javadoc.StandardJavadocDocletOptions;

import net.fabricmc.filament.task.CheckMappingsTask;
import net.fabricmc.filament.task.ConvertMappingsTask;
import net.fabricmc.filament.task.DecompileTask;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;

public final class FilamentGradlePlugin implements Plugin<Project> {
	@Override
	public void apply(Project project) {
		project.getPluginManager().apply("net.fabricmc.fabric-loom");

		final TaskContainer tasks = project.getTasks();
		final Directory mappings = project.getLayout().getProjectDirectory().dir("mappings");
		// Minecraft is not obfuscated, so the named jars are the same as the official jars
		final FileCollection minecraftJars = project.getExtensions().getByType(LoomGradleExtensionAPI.class).getNamedMinecraftJars();
		final Configuration minecraftLibraries = project.getConfigurations().getByName("minecraftLibraries");

		final Configuration decompileClasspath = project.getConfigurations().create("decompileClasspath", configuration -> {
			configuration.setCanBeConsumed(false);
			configuration.extendsFrom(minecraftLibraries);
		});

		var checkMappings = tasks.register("checkMappings", CheckMappingsTask.class, task -> {
			task.getMappings().set(mappings);
			task.getMinecraftJars().from(minecraftJars);
		});
		tasks.register("convertMappings", ConvertMappingsTask.class, task -> {
			task.getMappings().set(mappings);
			task.getOutput().set(project.getLayout().getBuildDirectory().file("mappings.tiny"));
		});
		var decompile = tasks.register("decompile", DecompileTask.class, task -> {
			task.getMappings().set(mappings);
			task.getMinecraftJars().from(minecraftJars);
			task.getLibraries().from(decompileClasspath);
			task.getOutput().set(project.getLayout().getBuildDirectory().dir("decompiled"));
		});

		// Checks the javadocs in the decompiled sources of the documented classes
		tasks.named("javadoc", Javadoc.class, task -> {
			task.setSource(decompile);
			task.setClasspath(project.files(minecraftJars, decompileClasspath));

			// downcast, see https://github.com/gradle/gradle/issues/7038
			StandardJavadocDocletOptions options = (StandardJavadocDocletOptions) task.getOptions();
			options.quiet();
			options.setMemberLevel(JavadocMemberLevel.PRIVATE);
			// Missing javadocs are allowed, as only some members are documented
			options.addBooleanOption("Xdoclint:all,-missing", true);
			// Decompiler errors in declarations would otherwise stop the javadocs from being checked
			options.addBooleanOption("-ignore-source-errors", true);
		});

		tasks.named("check", task -> task.dependsOn(checkMappings, "javadoc"));
		
		// checkMappings has no outputs, so it is not automatically ordered after clean
		checkMappings.configure(task -> task.mustRunAfter("clean"));
	}
}
