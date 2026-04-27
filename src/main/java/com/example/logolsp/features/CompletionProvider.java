package com.example.logolsp.features;

import com.example.logolsp.analysis.Scope;
import com.example.logolsp.analysis.Symbol;
import com.example.logolsp.analysis.SymbolTable;
import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.document.ParsedDocument;
import com.example.logolsp.parser.LogoKeywords;
import com.example.logolsp.parser.ast.Ast.ProcedureDef;
import com.example.logolsp.parser.ast.Ast.Program;
import com.example.logolsp.parser.ast.Ast.TopLevel;
import com.example.logolsp.util.Ranges;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Computes completion proposals for {@code textDocument/completion}.
 *
 * <p>Context detection is lightweight: the character immediately before the cursor
 * selects between variable completions ({@code :} prefix) and the general pool of
 * keywords + built-ins + user procedures. The LSP client filters by prefix, so we
 * return the full relevant set and let the editor narrow it.
 *
 * <p>Variable completions walk the scope chain of the enclosing {@link ProcedureDef}
 * (or the global scope at top level), so an edit cursor inside {@code TO square :size}
 * sees {@code :size} but not parameters of some other procedure.
 *
 * <p>The keyword list comes from {@link LogoKeywords}; control-flow primitives like
 * {@code IF}, {@code REPEAT}, {@code MAKE} arrive via the builtins registry, since
 * they are callable in the same way as user procedures.
 */
public final class CompletionProvider {

    private CompletionProvider() {}

    /** Returns completions at the given cursor position. */
    public static List<CompletionItem> completion(ParsedDocument doc, LogoBuiltins builtins, Position pos) {
        return completion(doc, builtins, pos, NOOP);
    }

    /** Same as {@link #completion(ParsedDocument, LogoBuiltins, Position)}, but cooperates with cancellation. */
    public static List<CompletionItem> completion(ParsedDocument doc, LogoBuiltins builtins,
                                                  Position pos, CancelChecker checker) {
        List<CompletionItem> items = new ArrayList<>();
        char before = charBefore(doc.text(), pos);
        if (before == ':') {
            addVariables(doc, pos, items, checker);
        } else {
            addKeywords(items, checker);
            addBuiltins(builtins, items, checker);
            addUserProcedures(doc.symbolTable(), items, checker);
        }
        return items;
    }

    private static void addVariables(ParsedDocument doc, Position pos, List<CompletionItem> items, CancelChecker checker) {
        Scope scope = scopeAt(doc, pos);
        Set<String> seen = new HashSet<>();
        for (Scope s = scope; s != null; s = s.parent()) {
            checker.checkCanceled();
            for (Symbol sym : s.localSymbols()) {
                if (sym.kind() == Symbol.Kind.PROCEDURE) continue;
                if (!seen.add(sym.name().toUpperCase(Locale.ROOT))) continue;
                CompletionItem item = new CompletionItem(sym.name());
                item.setKind(kindOf(sym));
                item.setDetail(labelFor(sym));
                items.add(item);
            }
        }
    }

    private static void addKeywords(List<CompletionItem> items, CancelChecker checker) {
        for (String kw : LogoKeywords.ALL) {
            checker.checkCanceled();
            CompletionItem item = new CompletionItem(kw);
            item.setKind(CompletionItemKind.Keyword);
            items.add(item);
        }
    }

    private static void addBuiltins(LogoBuiltins builtins, List<CompletionItem> items, CancelChecker checker) {
        for (LogoBuiltins.Builtin b : builtins.all()) {
            checker.checkCanceled();
            for (String name : b.allNames()) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Function);
                item.setDetail(b.canonicalName() + " (arity " + b.arity() + ")");
                item.setDocumentation(b.doc());
                items.add(item);
            }
        }
    }

    private static void addUserProcedures(SymbolTable table, List<CompletionItem> items, CancelChecker checker) {
        for (Symbol sym : table.global().localSymbols()) {
            checker.checkCanceled();
            if (sym.kind() != Symbol.Kind.PROCEDURE) continue;
            CompletionItem item = new CompletionItem(sym.name());
            item.setKind(CompletionItemKind.Function);
            item.setDetail("user procedure");
            items.add(item);
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static char charBefore(String text, Position pos) {
        int offset = positionToOffset(text, pos);
        return (offset > 0 && offset <= text.length()) ? text.charAt(offset - 1) : ' ';
    }

    private static int positionToOffset(String text, Position pos) {
        int line = 0, offset = 0;
        while (offset < text.length() && line < pos.getLine()) {
            if (text.charAt(offset) == '\n') line++;
            offset++;
        }
        offset += pos.getCharacter();
        return Math.min(offset, text.length());
    }

    private static Scope scopeAt(ParsedDocument doc, Position pos) {
        Program program = doc.program();
        for (TopLevel item : program.items()) {
            if (!(item instanceof ProcedureDef def)) continue;
            if (Ranges.contains(def.range(), pos)) return doc.symbolTable().scopeOf(def);
        }
        return doc.symbolTable().global();
    }

    private static CompletionItemKind kindOf(Symbol sym) {
        return switch (sym.kind()) {
            case PARAMETER, LOCAL -> CompletionItemKind.Variable;
            case GLOBAL -> CompletionItemKind.Variable;
            case PROCEDURE -> CompletionItemKind.Function;
        };
    }

    private static String labelFor(Symbol sym) {
        return switch (sym.kind()) {
            case PARAMETER -> "parameter";
            case LOCAL -> "local";
            case GLOBAL -> "global";
            case PROCEDURE -> "user procedure";
        };
    }

    private static final CancelChecker NOOP = () -> {};
}
