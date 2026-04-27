package com.example.logolsp.features;

import com.example.logolsp.document.ParsedDocument;
import com.example.logolsp.lexer.Token;
import com.example.logolsp.parser.ast.Ast.ProcedureDef;
import com.example.logolsp.parser.ast.Ast.TopLevel;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces the outline view for {@code textDocument/documentSymbol}.
 *
 * <p>Each user-defined {@code TO … END} block becomes a {@link SymbolKind#Function}
 * {@link DocumentSymbol}; its parameters are nested children of kind
 * {@link SymbolKind#Variable}. The {@code range} spans the entire block; the
 * {@code selectionRange} is the name token only, so clients can highlight just the
 * name when the symbol is revealed.
 *
 * <p>Anonymous procedure definitions — parser-recovered blocks whose name is the
 * empty string because the user typed {@code TO} but nothing after — are skipped
 * entirely; there's nothing useful to put in the outline for them.
 *
 * <p>Top-level commands (scripts at the bottom of a file) are deliberately not
 * surfaced: they're not structural landmarks, and including them would clutter the
 * outline.
 */
public final class DocumentSymbolProvider {

    private DocumentSymbolProvider() {}

    /** Returns one {@link DocumentSymbol} per {@code TO} definition (with parameter children). */
    public static List<DocumentSymbol> documentSymbols(ParsedDocument doc) {
        return documentSymbols(doc, NOOP);
    }

    /** Same as {@link #documentSymbols(ParsedDocument)}, but cooperates with cancellation. */
    public static List<DocumentSymbol> documentSymbols(ParsedDocument doc, CancelChecker checker) {
        List<DocumentSymbol> result = new ArrayList<>();
        for (TopLevel item : doc.program().items()) {
            checker.checkCanceled();
            if (!(item instanceof ProcedureDef def)) continue;
            if (def.nameToken().lexeme().isEmpty()) continue;
            result.add(symbolFor(def));
        }
        return result;
    }

    private static final CancelChecker NOOP = () -> {};

    private static DocumentSymbol symbolFor(ProcedureDef def) {
        DocumentSymbol symbol = new DocumentSymbol();
        symbol.setName(def.nameToken().lexeme());
        symbol.setKind(SymbolKind.Function);
        symbol.setRange(def.range());
        symbol.setSelectionRange(def.nameToken().range());

        if (!def.parameterTokens().isEmpty()) {
            StringBuilder detail = new StringBuilder();
            for (Token p : def.parameterTokens()) {
                if (detail.length() > 0) detail.append(' ');
                detail.append(p.lexeme());
            }
            symbol.setDetail(detail.toString());

            List<DocumentSymbol> children = new ArrayList<>(def.parameterTokens().size());
            for (Token p : def.parameterTokens()) {
                DocumentSymbol child = new DocumentSymbol();
                child.setName(p.lexeme());
                child.setKind(SymbolKind.Variable);
                child.setRange(p.range());
                child.setSelectionRange(p.range());
                children.add(child);
            }
            symbol.setChildren(children);
        }
        return symbol;
    }
}
