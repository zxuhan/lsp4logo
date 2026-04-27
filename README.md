# logo-lsp

A Language Server Protocol (LSP) implementation for the LOGO programming language,
targeting the [Turtle Academy](https://turtleacademy.com/) dialect (lessons:
<https://turtleacademy.com/lessons>, interactive playground:
<https://turtleacademy.com/playground>). The server speaks LSP over stdio (default) or
a TCP socket, and is designed to be consumed by
[LSP4IJ](https://github.com/redhat-developer/lsp4ij) in JetBrains IDEs or any other
generic LSP client.

---

## Features

Required:

- [x] **Syntax highlighting** via LSP semantic tokens
- [x] **Go-to-declaration** for user-defined procedures and variables (`:var` /
  parameter / `LOCAL` / `MAKE`)

Bonuses:

- [x] **Diagnostics** — syntax errors, missing `END`, too-few/too-many arguments,
  duplicate procedure, duplicate parameter, unknown procedure, undefined
  variable, unused parameter (warning), unused local (warning)
- [x] **Completion** — context-aware: variables after `:`, keywords + built-ins +
  user procedures elsewhere
- [x] **Hover** — signature + docs for built-ins, signature for user procedures
  (with any contiguous `;`-prefixed comment block above the `TO` lifted in as
  a doc comment), kind label for variables
- [x] **Document symbols** — outline view of every `TO` block, parameters as
  children

Deferred (see [Known limitations](#known-limitations)):

- [ ] Find-references
- [ ] Rename

---

## Build

Requires **JDK 17+**. The included Gradle wrapper installs everything else.

```sh
./gradlew shadowJar
```

Produces a single runnable fat jar at `build/libs/logo-lsp.jar`.

---

## Run

**stdio** (the LSP default):

```sh
java -jar build/libs/logo-lsp.jar
```

**TCP socket** (useful when attaching a debugger or driving from an integration test):

```sh
java -jar build/libs/logo-lsp.jar --socket 2087
```

The server listens on `127.0.0.1:<port>` and accepts a single connection.

All logging is routed to **stderr**; stdout is reserved for LSP's JSON-RPC framing.

---

## Connect to LSP4IJ (JetBrains IDEs)

1. Install the [**LSP4IJ**](https://plugins.jetbrains.com/plugin/23257-lsp4ij) plugin
   from the JetBrains Marketplace (Settings → Plugins → Marketplace → "LSP4IJ").
2. Open **Settings → Languages & Frameworks → Language Servers** (added by LSP4IJ).
3. Click **+ New Language Server**:
   - **Name:** `logo-lsp`
   - **Command:** `java -jar /absolute/path/to/build/libs/logo-lsp.jar`
   - **Associated file type or pattern:** `*.logo`
4. Create or open a `*.logo` file. The server starts automatically on first open;
   its stderr appears in the LSP Console tool window.

The same jar works with any other generic-LSP client (VS Code's generic LSP extension,
Neovim's `nvim-lspconfig`, Emacs `lsp-mode`, …).

Manual verification steps per feature: see [`docs/manual-test.md`](docs/manual-test.md).

---

## Architecture

```
                        ┌──────────────┐
    LSP client ──stdio──▶│   Main.java  │
                        └──────┬───────┘
                               │
                    ┌──────────▼───────────┐
                    │  LogoLanguageServer  │  ← initialize / shutdown / exit
                    │  (LSP4J plumbing)    │
                    └─┬──────────────┬─────┘
                      │              │
        ┌─────────────▼────┐   ┌─────▼────────────┐
        │ LogoTextDocument │   │ LogoWorkspace    │
        │ Service          │   │ Service (stub)   │
        └───────┬──────────┘   └──────────────────┘
                │ dispatches to feature providers
                ▼
┌───────────────────────────────────────────────────────────┐
│ DocumentStore  URI → ParsedDocument (ConcurrentHashMap)   │
└─────────────────────────┬─────────────────────────────────┘
                          │  on didOpen/didChange
                          ▼
         ┌─────────┐    ┌──────────┐    ┌────────────┐
         │ Lexer   │──▶ │ Parser   │──▶ │ Analyzer   │
         │ Token[] │    │ AST +    │    │ SymbolTable│
         │         │    │ parse    │    │ + semantic │
         │         │    │ diags    │    │ diags      │
         └─────────┘    └──────────┘    └────────────┘
                          │
                          ▼
         ┌─────────────────────────────────────────────┐
         │ feature providers (stateless, per-request)  │
         │   DefinitionProvider     HoverProvider      │
         │   SemanticTokensProvider CompletionProvider │
         │   DocumentSymbolProvider                    │
         └─────────────────────────────────────────────┘
```

Diagnostics aren't a separate provider: the parser and analyzer collect them as they
build the `ParsedDocument`, and the document service pushes them via
`LanguageClient.publishDiagnostics()` on every `didOpen` / `didChange`.

**Invariants:**

- `ParsedDocument` is immutable; every field is derived from the text.
- Full reparse on every `didChange` (LOGO files are small; incremental parsing isn't
  worth the complexity — see [ADR-005](#design-decisions)).
- Feature providers are stateless, take `(ParsedDocument, params, CancelChecker)` and
  return LSP types. They're trivially unit-testable, thread-safe, and cooperate with
  LSP cancellation — long AST walks check `checker.checkCanceled()` between recursions
  so superseded requests abort instead of running to completion.
- Parser recovers at `NEWLINE` / `END` / `TO` / `]`; it never throws.
- Built-in primitives are *data*, loaded from `builtins.json`.

---

## Project layout

```
logo-lsp/
├── build.gradle.kts, settings.gradle.kts  # Kotlin DSL + Shadow plugin
├── gradle/ gradlew, gradlew.bat            # Gradle 8.10 wrapper
├── README.md                               # this file
├── docs/manual-test.md                     # manual LSP4IJ checklist
└── src/
    ├── main/java/com/example/logolsp/
    │   ├── Main.java                        # stdio + --socket launcher
    │   ├── server/
    │   │   ├── LogoLanguageServer.java
    │   │   ├── LogoTextDocumentService.java
    │   │   └── LogoWorkspaceService.java
    │   ├── document/
    │   │   ├── DocumentStore.java           # thread-safe URI → ParsedDocument
    │   │   └── ParsedDocument.java
    │   ├── lexer/
    │   │   ├── Token.java, TokenType.java
    │   │   └── LogoLexer.java
    │   ├── parser/
    │   │   ├── LogoParser.java              # recursive-descent, error-recovering
    │   │   ├── LogoKeywords.java            # canonical {TO, END} set
    │   │   ├── ParseResult.java
    │   │   └── ast/Ast.java                 # sealed hierarchy, 16 node types
    │   ├── analysis/
    │   │   ├── Symbol.java, Scope.java
    │   │   ├── SymbolTable.java
    │   │   └── Analyzer.java
    │   ├── builtins/
    │   │   └── LogoBuiltins.java            # loads builtins.json via Gson
    │   ├── features/
    │   │   ├── DefinitionProvider.java
    │   │   ├── SemanticTokensProvider.java
    │   │   ├── CompletionProvider.java
    │   │   ├── HoverProvider.java
    │   │   └── DocumentSymbolProvider.java
    │   └── util/
    │       ├── Ranges.java                  # half-open LSP range containment
    │       └── Names.java                   # sigil-stripping for :var / "word
    ├── main/resources/
    │   └── builtins.json                    # Turtle-Academy-consistent primitives
    └── test/
        ├── java/...                         # JUnit 5 + AssertJ (mirrors main tree)
        └── resources/fixtures/*.logo        # programs that paste-run in the playground
```

---

## Design decisions

The most load-bearing choices. (The full ADR log lives next to this file during
development; only the important ones are inlined here.)

- **Java 17, pure Java** (no Kotlin). Pure Java reads like LSP4J's own codebase,
  minimising cognitive overhead for the reviewer. Records + sealed interfaces give
  us ergonomic immutable AST value types without Kotlin-specific build quirks.
- **Hand-written recursive-descent parser** (no ANTLR / JavaCC). The Kotlin
  compiler and IntelliJ PSI are both hand-written for exactly this reason: LSPs
  parse half-typed input, so graceful error recovery beats a terse generator
  grammar. LOGO is small enough that a hand-written parser fits in one focused
  file, and error messages can be tuned precisely.
- **Two-pass parser** (ADR-008). Pass 1 scans every `TO` header into an arity
  table; pass 2 uses the combined builtins + user arity map to consume the right
  number of arguments per call. This is what lets forward references work — a
  call to `foo` before `TO foo … END` has been seen still parses correctly.
- **Full reparse on `didChange`** (ADR-005). The `ParsedDocument` is immutable;
  every change produces a fresh one, which is trivially race-free. Incremental
  parsing was evaluated and rejected as bug-prone for files this size.
- **Semantic tokens, not TextMate** (ADR-011). The server already has a parser
  and symbol table; emitting semantic tokens reuses that knowledge instead of
  duplicating it in a regex grammar.
- **Lexical scoping for a dynamically-scoped language** (ADR-007). LOGO is
  traditionally dynamically scoped, but for LSP navigation that's useless — the
  user wants "jump to the nearest enclosing `:x`," which is lexical.
- **Two true keywords, not nine.** Only `TO` and `END` — the procedure delimiters —
  are syntax. `IF`, `IFELSE`, `REPEAT`, `MAKE`, `LOCAL`, `OUTPUT`, `STOP` and the
  rest are callable primitives (data in `builtins.json`), so they're highlighted
  and completed as functions with the `defaultLibrary` modifier rather than as
  keywords. Keeping the keyword set tiny avoids the bug where the keyword list
  and the builtins list disagree.
- **Turtle-Academy-only dialect scope** (ADR-010). `builtins.json` is populated
  only with primitives observable in Turtle Academy's lessons and playground.
  Other LOGO dialects (UCBLogo, MSWLogo, Berkeley Logo, NetLogo) are explicitly
  out of scope.

---

## Running tests

```sh
./gradlew check
```

Runs the full JUnit 5 + AssertJ suite: lexer, parser, analyzer, every feature
provider, a per-provider cancellation contract test, and an in-process LSP4J
integration test that drives `initialize` → `didOpen` → `textDocument/definition`
over piped streams.

The suite currently has **155 tests across 14 test classes**; zero warnings.

---

## Known limitations

- **Find-references** is not implemented (only its inverse, go-to-definition).
  Inverting the reference walk would land it.
- **Rename** is not implemented; it would build on find-references.
- **Incremental reparse** (`TextDocumentSyncKind.Incremental`) is not supported;
  we advertise `Full` sync. For LOGO-size files this is microseconds.
- **Workspace-level features** — no `workspace/symbol`, no cross-file symbol
  resolution, no file watchers. One LOGO source file is one analysis unit.
- **Signature help** during function-call argument typing is not implemented.
- **Dialect verification** — the Turtle Academy dialect decisions (case
  sensitivity, forward references, exact primitive list) are validated against
  the playground but not exhaustively. See `docs/manual-test.md`.

---

## Turtle Academy references

All dialect decisions — primitive names, arity, syntax, keyword set —
trace to the Turtle Academy lesson pages and playground behaviour.
Pasting any fixture from `src/test/resources/fixtures/*.logo` into
<https://turtleacademy.com/playground> should run it.
