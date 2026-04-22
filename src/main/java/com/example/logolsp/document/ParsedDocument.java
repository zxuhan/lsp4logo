package com.example.logolsp.document;

import com.example.logolsp.analysis.Analyzer;
import com.example.logolsp.analysis.SymbolTable;
import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.lexer.LogoLexer;
import com.example.logolsp.lexer.Token;
import com.example.logolsp.parser.LogoParser;
import com.example.logolsp.parser.ParseResult;
import com.example.logolsp.parser.ast.Ast.Program;
import org.eclipse.lsp4j.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of one document's analysis pipeline.
 *
 * <p>Produced fresh on every {@code didOpen} / {@code didChange}. Because every field
 * is derived from {@link #text()} and every downstream value type is immutable, this
 * record is safe to share across feature-provider threads.
 *
 * <p>{@link #diagnostics()} concatenates parser syntax diagnostics and analyzer
 * semantic diagnostics into one list — the LSP client doesn't care which layer
 * produced them.
 */
public record ParsedDocument(
        String uri,
        String text,
        List<Token> tokens,
        Program program,
        SymbolTable symbolTable,
        List<Diagnostic> diagnostics) {

    public ParsedDocument {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(symbolTable, "symbolTable");
        Objects.requireNonNull(diagnostics, "diagnostics");
        tokens = List.copyOf(tokens);
        diagnostics = List.copyOf(diagnostics);
    }

    /** Runs the full lex → parse → analyse pipeline for a single document. */
    public static ParsedDocument parse(String uri, String text, LogoBuiltins builtins) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(builtins, "builtins");
        List<Token> tokens = new LogoLexer(text).tokenize();
        ParseResult parseResult = new LogoParser(tokens, builtins).parse();
        SymbolTable symbolTable = Analyzer.analyze(parseResult.program(), builtins);
        List<Diagnostic> combined = new ArrayList<>(
                parseResult.diagnostics().size() + symbolTable.diagnostics().size());
        combined.addAll(parseResult.diagnostics());
        combined.addAll(symbolTable.diagnostics());
        return new ParsedDocument(uri, text, tokens, parseResult.program(), symbolTable, combined);
    }
}
