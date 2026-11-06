package net.fabricmc.filament.task;

import java.io.IOException;
import java.util.Map;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;

/// Checks that all the javadocs are in the decompiled sources, and do not contain the marker used to find them.
public abstract class CheckDecompileTask extends DefaultTask {
	/// The [DecompileTask#getReport()] of the mappings.
	@InputFile
	public abstract RegularFileProperty getReport();

	@TaskAction
	public void run() throws IOException {
		Map<String, String> errors = DecompileTask.readReport(getReport().get().getAsFile().toPath());

		if (!errors.isEmpty()) {
			errors.forEach((element, reason) -> getLogger().error("{} {}", element, reason));
			throw new GradleException("Found " + errors.size() + " invalid javadocs, see the log for details");
		}
	}
}
