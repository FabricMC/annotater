package net.fabricmc.filament;

import java.util.List;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.external.javadoc.StandardJavadocDocletOptions;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.jvm.toolchain.JvmVendorSpec;

import net.fabricmc.filament.task.CheckDecompileTask;
import net.fabricmc.filament.task.CheckMappingsTask;
import net.fabricmc.filament.task.ConvertMappingsTask;
import net.fabricmc.filament.task.DecompileTask;
import net.fabricmc.filament.task.DropCheckMappingsErrorsTask;
import net.fabricmc.filament.task.DropDecompileErrorsTask;
import net.fabricmc.filament.task.DropJavadocErrorsTask;
import net.fabricmc.filament.task.FixMappingsTask;
import net.fabricmc.filament.task.FormatMappingsTask;
import net.fabricmc.filament.task.JavaRuntimeJarTask;
import net.fabricmc.filament.task.unpick.CheckUnpickDefinitionsTask;
import net.fabricmc.filament.task.unpick.CombineUnpickDefinitionsTask;
import net.fabricmc.filament.task.unpick.UnpickJarTask;
import net.fabricmc.filament.util.FilamentConstants;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;

public final class FilamentGradlePlugin implements Plugin<Project> {
	private static final String ANNOTATER_TASK_GROUP = "annotater";

	@Override
	public void apply(Project project) {
		project.getPluginManager().apply("net.fabricmc.fabric-loom");

		final TaskContainer tasks = project.getTasks();
		final Directory mappings = project.getLayout().getProjectDirectory().dir("mappings");
		final DirectoryProperty buildDir = project.getLayout().getBuildDirectory();
		// Minecraft is not obfuscated, so the named jars are the same as the official jars
		final FileCollection minecraftJars = project.getExtensions().getByType(LoomGradleExtensionAPI.class).getNamedMinecraftJars();
		final Configuration minecraftLibraries = project.getConfigurations().getByName("minecraftLibraries");

		final Configuration decompileClasspath = project.getConfigurations().create("decompileClasspath", configuration -> {
			configuration.setCanBeConsumed(false);
			configuration.extendsFrom(minecraftLibraries);
		});

		var minecraftJavaVersion = project.getProviders().provider(() -> {
			var versionInfo = LoomGradleExtension.get(project).getMinecraftProvider().getVersionInfo();
			return JavaLanguageVersion.of(versionInfo.javaVersion().majorVersion());
		});
		var javaToolchains = project.getExtensions().getByType(JavaToolchainService.class);
		var javaLauncher = javaToolchains.launcherFor(spec -> {
			spec.getLanguageVersion().set(minecraftJavaVersion);
			spec.getVendor().set(JvmVendorSpec.MICROSOFT);
		});
		var javaRuntimeJar = tasks.register("javaRuntimeJar", JavaRuntimeJarTask.class, task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.getJavaHome().set(javaLauncher.map(launcher -> launcher.getMetadata().getInstallationPath()));
			task.getOutput().set(buildDir.file("javaRuntime/java-runtime.jar"));
		});
		var syncRuntimeLibs = tasks.register("syncRuntimeLibs", Sync.class, task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.setDescription("Syncs Minecraft dependencies and Java runtime classes, excluding the Minecraft jars.");
			task.from(decompileClasspath);
			task.from(javaRuntimeJar.flatMap(JavaRuntimeJarTask::getOutput));
			task.include("*.jar");
			task.into(buildDir.dir("runtime_libs"));
		});
		tasks.register("prepareMcSrc", task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.setDescription("Prepares the project for mcsrc.");
			task.dependsOn(syncRuntimeLibs);
		});

		final Directory unpickDefinitions = project.getLayout().getProjectDirectory().dir("unpick-definitions");
		var checkUnpickDefinitions = tasks.register("checkUnpickDefinitions", CheckUnpickDefinitionsTask.class, task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.getInput().set(unpickDefinitions);
			task.getClasspath().from(minecraftJars, decompileClasspath, javaRuntimeJar.flatMap(JavaRuntimeJarTask::getOutput));
		});
		var combineUnpickDefinitions = tasks.register("combineUnpickDefinitions", CombineUnpickDefinitionsTask.class, task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.dependsOn(checkUnpickDefinitions);
			task.getInput().set(unpickDefinitions);
			task.getOutput().set(buildDir.file("unpick/definitions.unpick"));
		});
		var unpickJar = tasks.register("unpickJar", UnpickJarTask.class, task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.setDescription("Applies unpick definitions to the Minecraft jar.");
			task.getInput().set(project.getLayout().file(minecraftJars.getElements().map(files -> {
				if (files.size() != 1) {
					throw new IllegalStateException("Unpick requires a single merged Minecraft jar");
				}

				return files.iterator().next().getAsFile();
			})));
			task.getUnpickDefinitions().set(combineUnpickDefinitions.flatMap(CombineUnpickDefinitionsTask::getOutput));
			task.getClasspath().from(decompileClasspath, javaRuntimeJar.flatMap(JavaRuntimeJarTask::getOutput));
			task.getOutput().set(buildDir.file("unpick/minecraft-unpicked.jar"));
		});
		tasks.register("decompileVineflower", DecompileTask.class, task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.setDescription("Decompiles the whole game with unpick and javadocs applied.");
			task.getDecompileAll().set(true);
			task.getMappings().set(mappings);
			task.getMinecraftJars().from(unpickJar.flatMap(UnpickJarTask::getOutput));
			task.getLibraries().from(decompileClasspath);
			task.getOutput().set(buildDir.dir("vineflower"));
			task.getReport().set(buildDir.file("decompileVineflower/report.tsv"));
			task.getJavadocLines().set(buildDir.file("decompileVineflower/javadoc-lines.tsv"));
		});
		// Loom registers genSources after evaluation as a lifecycle task for its default decompiler.
		project.afterEvaluate(ignored -> tasks.named("genSources", task -> {
			task.setEnabled(false);
			// Gradle still runs the dependencies of a disabled task.
			task.setDependsOn(List.of());
		}));

		var checkMappings = tasks.register("checkMappings", CheckMappingsTask.class, task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.getMappings().set(mappings);
			task.getMinecraftJars().from(minecraftJars);
		});
		tasks.register("convertMappings", ConvertMappingsTask.class, task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.getMappings().set(mappings);
			task.getOutput().set(buildDir.file("mappings.tiny"));
		});
		var decompile = tasks.register("decompile", DecompileTask.class, task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.getMappings().set(mappings);
			task.getMinecraftJars().from(minecraftJars);
			task.getLibraries().from(decompileClasspath);
			task.getOutput().set(buildDir.dir("decompiled"));
			task.getReport().set(buildDir.file("decompile/report.tsv"));
			task.getJavadocLines().set(buildDir.file("decompile/javadoc-lines.tsv"));
		});
		var checkDecompile = tasks.register("checkDecompile", CheckDecompileTask.class, task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.getReport().set(decompile.flatMap(DecompileTask::getReport));
		});

		// Checks the javadocs in the decompiled sources of the documented classes
		tasks.named("javadoc", Javadoc.class, task -> {
			task.setSource(decompile.flatMap(DecompileTask::getOutput));
			task.setClasspath(project.files(minecraftJars, decompileClasspath));

			// downcast, see https://github.com/gradle/gradle/issues/7038
			StandardJavadocDocletOptions options = (StandardJavadocDocletOptions) task.getOptions();
			// Gradle adds the leading "-"
			FilamentConstants.JAVADOC_OPTIONS.forEach(flag -> options.addBooleanOption(flag.substring(1), true));
		});

		tasks.named("check", task -> task.dependsOn(checkMappings, checkDecompile, unpickJar, "javadoc"));

		// The check tasks have no outputs, so they are not automatically ordered after clean
		checkMappings.configure(task -> task.mustRunAfter("clean"));
		checkDecompile.configure(task -> task.mustRunAfter("clean"));
		checkUnpickDefinitions.configure(task -> task.mustRunAfter("clean"));

		// A pipeline fixing the mappings, each step dropping the mappings failing one of the checks
		var dropCheckMappingsErrors = tasks.register("dropCheckMappingsErrors", DropCheckMappingsErrorsTask.class, task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.getMappings().set(mappings);
			task.getMinecraftJars().from(minecraftJars);
			task.getOutput().set(buildDir.dir("fixedMappings/dropCheckMappingsErrors"));
		});
		var dropDecompileErrors = tasks.register("dropDecompileErrors", DropDecompileErrorsTask.class, task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.getMappings().set(dropCheckMappingsErrors.flatMap(FixMappingsTask::getOutput));
			task.getReport().set(decompile.flatMap(DecompileTask::getReport));
			task.getOutput().set(buildDir.dir("fixedMappings/dropDecompileErrors"));
		});
		var dropJavadocErrors = tasks.register("dropJavadocErrors", DropJavadocErrorsTask.class, task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.getMappings().set(dropDecompileErrors.flatMap(FixMappingsTask::getOutput));
			task.getSources().set(decompile.flatMap(DecompileTask::getOutput));
			task.getJavadocLines().set(decompile.flatMap(DecompileTask::getJavadocLines));
			task.getClasspath().from(minecraftJars, decompileClasspath);
			task.getOutput().set(buildDir.dir("fixedMappings/dropJavadocErrors"));
		});
		var formatMappings = tasks.register("formatMappings", FormatMappingsTask.class, task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.getMappings().set(dropJavadocErrors.flatMap(FixMappingsTask::getOutput));
			task.getOutput().set(buildDir.dir("fixedMappings/formatMappings"));
		});
		Provider<Directory> fixedMappings = formatMappings.flatMap(FixMappingsTask::getOutput);
		var dropInvalidMappings = tasks.register("dropInvalidMappings", Sync.class, task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.setDescription("Drops the mappings failing the checks, replacing the mappings with the fixed mappings.");
			task.from(fixedMappings);
			task.into(mappings);
		});
		tasks.register("dropInvalid", task -> {
			task.setGroup(ANNOTATER_TASK_GROUP);
			task.setDescription("Drops everything failing the checks.");
			task.dependsOn(dropInvalidMappings);
		});
	}
}
