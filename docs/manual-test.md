# Manual verification checklist

End-to-end confirmation that the server works in a real IDE. Run through this before
shipping.

## 0. Prerequisites

- Recent IntelliJ IDEA (Community or Ultimate, 2024.2+).
- JDK 17 or newer on `PATH`.
- [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) installed from the
  JetBrains Marketplace.

## 1. Build the jar

```sh
./gradlew shadowJar
```

Confirm `build/libs/logo-lsp.jar` exists and is ~1 MB.

## 2. Register the server in LSP4IJ

1. In IntelliJ: **Settings → Languages & Frameworks → Language Servers**.
2. **+ New Language Server**:
   - **Name:** `logo-lsp`
   - **Command:** `java -jar /absolute/path/to/build/libs/logo-lsp.jar`
   - **Mappings → File name patterns:** `*.logo`
3. Apply. A new **LSP Console** tool window appears (View → Tool Windows → LSP Console).

## 3. Open a fixture

Open `src/test/resources/fixtures/square.logo`. The LSP Console should show:

```
INFO: Starting LOGO LSP over stdio
INFO: initialize from IntelliJ
```

No errors in the console. Client-side, the file should be recognised as LOGO by LSP4IJ.

## 4. Feature walkthrough

For each feature below, confirm the behaviour. If any step fails, check the LSP Console
for stack traces on the server side.

### 4.1 Semantic highlighting

- `TO` / `END` — keyword colour (the only true syntax keywords)
- `REPEAT` / `FD` / `RT` / `MAKE` — function colour, with a "built-in" emphasis
  (typically a different hue from user-defined)
- `square` in `TO square` — function colour, with a subtle "declaration" emphasis
- `:size` — parameter colour
- `4`, `90`, `100` — number colour
- `+`, `*`, `/` in `polygon.logo` — operator colour
- `; comment` in `forward_ref.logo` — comment colour

### 4.2 Go-to-definition

- Ctrl/Cmd-click `:size` inside `FD :size` → cursor jumps to `:size` in the `TO square`
  header.
- Ctrl/Cmd-click `square` at the bottom → cursor jumps to `TO square`.
- Ctrl/Cmd-click `FD` → no navigation (it's a built-in, has no source location).

### 4.3 Completion

- Open an empty line inside a `TO` body. Type **Ctrl-Space**. You should see:
  the `TO` / `END` keywords, all built-ins (FORWARD, FD, REPEAT, IF, MAKE, …), and
  any user-defined procedures in the file.
- Type a colon `:`. The trigger kicks in, filtering the list to *only* in-scope
  variables (parameters + `LOCAL`s + globals). The colon-prefix does not appear in
  completion labels.

### 4.4 Hover

- Hover over `FD` → tooltip with `FORWARD (aliases: FD) — arity 1` and the doc string
  "Move the turtle forward by N steps along its current heading." followed by
  "*Turtle Academy built-in*".
- Hover over `square` (at a call site) → tooltip with `TO square :size` signature.
- If you add `; comments` immediately above the `TO`, those lines appear in the
  tooltip as the procedure's doc comment.
- Hover over `:size` → tooltip with kind `(parameter)`.

### 4.5 Diagnostics

Open `src/test/resources/fixtures/broken.logo`. Expect:

- A red squiggle on the bare `TO` at line 1: "expected procedure name after TO".
- No squiggles on `TO good :x / FD :x / END / good 50`.

Open a new file and try each:

- `HAMBURGER 1` → red squiggle "unknown procedure: HAMBURGER".
- `FD 10 20` → red squiggle "too many arguments for FD (expected 1)".
- `SETXY 1` → red squiggle "too few arguments for SETXY (expected 2, got 1)".
- `TO foo :x\n  FD 100\nEND` → yellow squiggle on `:x`: "unused parameter: :x".

### 4.6 Outline view

Open the structure view (Cmd-7 on macOS, Alt-7 on Windows/Linux). You should see:

```
greet                                (Function)
square :size                         (Function)
  :size                              (Variable)
polygon :sides :size                 (Function)
  :sides                             (Variable)
  :size                              (Variable)
```

depending on which fixture is open. Clicking a symbol navigates to it.

## 5. Shut down cleanly

Close the `.logo` file. LSP Console should log `INFO: shutdown requested` followed by
`INFO: exit (code=0)`. If you see a non-zero exit code, something went wrong — check
the console output.

## 6. If something is off

- **Server doesn't start:** check `java --version` (need 17+), confirm the jar path,
  check the LSP Console for a Java stack trace.
- **No highlighting / completion:** confirm the file is associated with the LOGO
  language server under LSP4IJ settings (step 2.ii).
- **Diagnostics on fixtures that should be clean:** make sure the fixture actually
  runs in <https://turtleacademy.com/playground>. Any primitive the playground rejects
  but `builtins.json` includes is a bug we want to fix.
