package com.example.logolsp.features;

import com.example.logolsp.analysis.Scope;
import com.example.logolsp.analysis.Symbol;
import com.example.logolsp.analysis.SymbolTable;
import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.document.ParsedDocument;
import com.example.logolsp.parser.ast.Ast.ProcedureDef;
import com.example.logolsp.parser.ast.Ast.Program;
import com.example.logolsp.parser.ast.Ast.TopLevel;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.List;
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
 */
public final class CompletionProvider {

    /** Language-level keywords; always available outside variable context. */
    private static final List<String> KEYWORDS = List.of(
            "TO", "END", "IF", "IFELSE", "REPEAT", "MAKE", "LOCAL", "OUTPUT", "STOP");

    private CompletionProvider() {}

    /** Returns completions at the given cursor position. */
    public static List<CompletionItem> completion(ParsedDocument doc, LogoBuiltins builtins, Position pos) {
        List<CompletionItem> items = new ArrayList<>();
        char before = charBefore(doc.text(), pos);
        if (before == ':') {
            addVariables(doc, pos, items);
        } else {
            addKeywords(items);
            addBuiltins(builtins, items);
            addUserProcedures(doc.symbolTable(), items);
        }
        return items;
    }

    private static void addVariables(ParsedDocument doc, Position pos, List<CompletionItem> items) {
        Scope scope = scopeAt(doc, pos);
        Set<String> seen = new java.util.HashSet<>();
        for (Scope s = scope; s != null; s = s.parent()) {
            for (Symbol sym : s.localSymbols()) {
                if (sym.kind() == Symbol.Kind.PROCEDURE) continue;
                if (!seen.add(sym.name().toUpperCase(java.util.Locale.ROOT))) continue;
                CompletionItem item = new CompletionItem(sym.name());
                item.setKind(kindOf(sym));
                item.setDetail(labelFor(sym));
                items.add(item);
            }
        }
    }

    private static void addKeywords(List<CompletionItem> items) {
        for (String kw : KEYWORDS) {
            CompletionItem item = new CompletionItem(kw);
            item.setKind(CompletionItemKind.Keyword);
            items.add(item);
        }
    }

    private static void addBuiltins(LogoBuiltins builtins, List<CompletionItem> items) {
        for (LogoBuiltins.Builtin b : builtins.all()) {
            for (String name : b.allNames()) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Function);
                item.setDetail(b.canonicalName() + " (arity " + b.arity() + ")");
                item.setDocumentation(b.doc());
                items.add(item);
            }
        }
    }

    private static void addUserProcedures(SymbolTable table, List<CompletionItem> items) {
        for (Symbol sym : table.global().localSymbols()) {
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
            if (contains(def.range(), pos)) return doc.symbolTable().scopeOf(def);
        }
        return doc.symbolTable().global();
    }

    private static boolean contains(Range range, Position p) {
        int pl = p.getLine(), pc = p.getCharacter();
        Position s = range.getStart(), e = range.getEnd();
        if (pl < s.getLine() || pl > e.getLine()) return false;
        if (pl == s.getLine() && pc < s.getCharacter()) return false;
        if (pl == e.getLine() && pc > e.getCharacter()) return false;
        return true;
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
}
