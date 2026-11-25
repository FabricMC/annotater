# annotater

Javadocs for Minecraft, free for everyone to use under the Creative Commons Zero license.

## Gradle tasks

| Command | Purpose |
| --- | --- |
| `./gradlew prepareMcSrc` | Prepare the project for editing with mcsrc by collecting Minecraft dependencies and Java runtime classes in `build/runtime_libs`. |
| `./gradlew check` | Validate the mappings, Javadocs, and unpick definitions. |
| `./gradlew decompileVineflower` | Decompile the whole game into `build/vineflower`, with unpick and Javadocs applied. |
| `./gradlew dropInvalid` | Remove mappings and Javadocs that fail validation, rewriting the files in `mappings`. |
