# Javadoc conventions

## Follow grammar conventions

Use American English spelling.

Use terminology consistent with Minecraft and nearby documentation.

Preserve capitalization and spelling when referring to code.

```diff
-/// leap over nearby fops when not attacking something
+/// Jump over nearby foxes as long as `this` is not aggressive.
public void jumpOverFoxes() { /*...*/ }
```

## Add value

Explain only what a developer cannot learn from the member's declaration. Leave obvious members undocumented.

Document only what a caller needs to know. Don't walk through implementation.

```diff
-/// Gets the width.
+/// Returns the width in pixels.
public double getWidth() { /*...*/ }

-/// Returns the id of this object.
public Identifier getId() { /*...*/ }
```
