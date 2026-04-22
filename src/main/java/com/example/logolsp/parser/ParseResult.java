package com.example.logolsp.parser;

import com.example.logolsp.parser.ast.Ast.Program;
import org.eclipse.lsp4j.Diagnostic;

import java.util.List;
import java.util.Objects;

/**
 * Immutable outcome of a {@link LogoParser} run: an AST plus any diagnostics collected
 * during parsing. Diagnostics are always syntax-level (missing tokens, unexpected
 * tokens); semantic diagnostics (undefined variables, arity mismatches in semantics
 * sense) live in the analyzer.
 */
public record ParseResult(Program program, List<Diagnostic> diagnostics) {
    public ParseResult {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(diagnostics, "diagnostics");
        diagnostics = List.copyOf(diagnostics);
    }
}
