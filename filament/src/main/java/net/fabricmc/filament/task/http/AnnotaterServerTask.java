package net.fabricmc.filament.task.http;

import javax.inject.Inject;

import com.google.gson.Gson;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.process.ExecOperations;

import net.fabricmc.mappingio.MappingVisitor;

public abstract class AnnotaterServerTask extends DefaultTask {
	@Inject
	protected abstract ExecOperations getExecOperations();

	@Inject
	protected abstract ObjectFactory getObjects();

	@InputDirectory
	public abstract DirectoryProperty getMappingsDir();

	@InputDirectory
	public abstract DirectoryProperty getUnpickDir();

	@InputDirectory
	public abstract DirectoryProperty getRuntimeLibrariesDir();

	@Input
	public abstract Property<String> getMinecraftVersion();

	@Input
	@Option(option = "host", description = "The host address to bind to (default: 127.0.0.1)")
	public abstract Property<String> getHost();

	@Input
	@Option(option = "port", description = "The port to listen on (default: 8000, use 0 for random)")
	public abstract Property<String> getPort();

	@Input
	@Option(option = "client", description = "The url of the mcsrc instance (default: https://mcsrc.dev)")
	public abstract Property<String> getClient();

	@Input
	@Option(option = "open", description = "Open the mcsrc instance on the browser (default: false)")
	public abstract Property<Boolean> getOpen();

	public AnnotaterServerTask() {
		setDescription("Serve HTTP server to be used with mcsrc");
		getHost().convention("127.0.0.1");
		getPort().convention("8000");
		getClient().convention("https://mcsrc.dev");
		getOpen().convention(false);
		getOutputs().upToDateWhen(_ -> false);
	}

	@TaskAction
	public void runServer() {
		getExecOperations().javaexec(spec -> {
			spec.setClasspath(getObjects().fileCollection().from(
					AnnotaterServer.class.getProtectionDomain().getCodeSource().getLocation(),
					Gson.class.getProtectionDomain().getCodeSource().getLocation(),
					MappingVisitor.class.getProtectionDomain().getCodeSource().getLocation()
			));
			spec.getMainClass().set(AnnotaterServer.class.getName());
			spec.systemProperty(AnnotaterServer.PROP_KEY, AnnotaterServer.GSON.toJson(new AnnotaterServer(
					getMappingsDir().get().getAsFile().getAbsolutePath(),
					getUnpickDir().get().getAsFile().getAbsolutePath(),
					getRuntimeLibrariesDir().get().getAsFile().getAbsolutePath(),
					getMinecraftVersion().get(),
					getHost().get(),
					getPort().map(Integer::parseInt).get(),
					getClient().get(),
					getOpen().get()
			)));
		});
	}
}
