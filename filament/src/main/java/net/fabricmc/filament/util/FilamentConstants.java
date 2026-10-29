package net.fabricmc.filament.util;

import java.util.List;

public final class FilamentConstants {
	public static final List<String> JAVADOC_OPTIONS = List.of(
			"-quiet",
			"-private",
			// Missing javadocs are allowed, as only some members are documented
			"-Xdoclint:all,-missing",
			// Decompiler errors in declarations would otherwise stop the javadocs from being checked
			"--ignore-source-errors"
	);

	private FilamentConstants() {
	}
}
