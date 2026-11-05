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

## Start with a summary

Write a concise first sentence that can stand alone. For methods, start with a verb such as "Returns," "Sets," or "Removes." Add a blank line before further explanation (if any is required).

```diff
+/// Iterates over a column of blocks, then over all its neighbor columns.
+/// 
/// Iterates from `(startX, startY, startZ)` until `y==endY` (exclusive), then does the same for each of the columns on the north, east, south, and west of the first column. Iterates upwards if `startY<endY`, downwards if `startY>endY`.
public static Iterable<BlockPos> neighborColumn(int startX, int startY, int startZ, int endY) { /*...*/ }
```
