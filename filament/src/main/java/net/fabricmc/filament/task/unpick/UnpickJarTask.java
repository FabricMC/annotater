package net.fabricmc.filament.task.unpick;

import java.io.IOException;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.task.service.UnpickService;
import net.fabricmc.loom.util.service.ScopedServiceFactory;

public abstract class UnpickJarTask extends DefaultTask {
	public UnpickJarTask() {
		var definitions = getProject().getProviders().fileContents(getUnpickDefinitions()).getAsBytes();
		getOptions().set(UnpickService.TYPE.create(getProject(), options -> {
			options.getUnpickDefinitions().set(definitions);
			options.getUnpickClasspath().from(getClasspath());
			options.getUnpickOutputJar().set(getOutput());
			options.getLenient().set(false);
		}));
	}

	@InputFile
	public abstract RegularFileProperty getInput();

	@InputFile
	public abstract RegularFileProperty getUnpickDefinitions();

	@CompileClasspath
	public abstract ConfigurableFileCollection getClasspath();

	@OutputFile
	public abstract RegularFileProperty getOutput();

	@Nested
	public abstract Property<UnpickService.Options> getOptions();

	@TaskAction
	public void run() throws IOException {
		try (var services = new ScopedServiceFactory()) {
			UnpickService service = services.get(getOptions());
			service.unpickJar(getInput().get().getAsFile().toPath(), null);
		}
	}
}
