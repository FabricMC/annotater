# Javadoc conventions

## Document what adds value

Explain what a developer cannot learn from the declaration, leave obvious members undocumented.

For example, `Gets the width.` adds nothing to `getWidth()`. If the unit matters, `Returns the width in pixels.` is useful.

Avoid descriptions such as `The position.` for `pos` or `The result.` for a return value.

## Start with a short summary

Write a concise first sentence that makes sense on its own. Capitalize it and end it with a period. For methods, start with a verb such as “Returns”, “Sets”, or “Removes”. Add a blank line before further explanation.

Use plain English and terminology consistent with Minecraft and nearby documentation. Describe behavior from the caller’s perspective rather than walking through the implementation.

Use American English spelling, such as “color” rather than “colour”.

Use lowercase for ordinary references to game content, such as “snow fox” rather than “Snow Fox”. Preserve capitalization when referring to code identifiers.

## Use Markdown Javadoc

Use backticks for identifiers, literals, and short expressions. Separate paragraphs with blank lines; prefer Markdown lists and fenced code blocks over HTML. Include a short code example when it explains usage better than prose.

Prefer Markdown reference links over `{@link}` and `{@linkplain}` for references to classes and members. For example:

```markdown
Use [java.util.List#copyOf(java.util.Collection)] to create an unmodifiable copy.
```

Use `#` to reference a member of the current class, such as `[#clear()]`. Use `[clearing the entries][#clear()]` for a custom label in plain text, or a block tag such as `@see #clear()` for a related reference.  Use ordinary Markdown links such as `[label](https://example.com)` for external URLs.

Link when it helps the reader find related documentation; repeating a link on every mention is unnecessary.

## Explain parameters and results only when needed

Use `@param`, `@return`, and `@throws` when they help the reader understand a parameter, result, or failure condition. Repeating information from the prose is fine when it makes the documentation clearer or easier to use. Parameter and return descriptions should be short phrases without an initial capital or final period.

Keep parameter descriptions focused on useful details. For example:

```markdown
@param namespace the namespace, restricted to `[a-z0-9_.-]`
```

For a hypothetical method whose timeout has a special value, this may be enough:

```markdown
Waits for pending work to finish.

@param timeout the maximum wait in milliseconds, or `0` to wait indefinitely
```

Other parameters can remain undocumented. Likewise, omit `@return` when the summary already explains the result. Use `@throws` to explain a relevant failure condition, not to list every exception the implementation could produce.

For example:

```markdown
@throws IdentifierException if the path contains a character outside `[a-z0-9/._-]`
```

Write the exception name immediately after `@throws` without backticks.

Inspired by [Yarn’s conventions](https://github.com/FabricMC/yarn/blob/1.21.11/CONVENTIONS.md) and [Effective Dart’s documentation guide](https://dart.dev/effective-dart/documentation).

For Markdown Javadoc syntax, see Oracle’s [Markdown in Documentation Comments](https://docs.oracle.com/en/java/javase/26/javadoc/using-markdown-documentation-comments.html) guide.
