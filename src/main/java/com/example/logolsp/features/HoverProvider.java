package com.example.logolsp.features;

import com.example.logolsp.analysis.Scope;
import com.example.logolsp.analysis.Symbol;
import com.example.logolsp.analysis.SymbolTable;
import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.document.ParsedDocument;
import com.example.logolsp.lexer.Token;
import com.example.logolsp.parser.ast.Ast.BinaryOp;
import com.example.logolsp.parser.ast.Ast.ColonVar;
import com.example.logolsp.parser.ast.Ast.Command;
import com.example.logolsp.parser.ast.Ast.Expression;
import com.example.logolsp.parser.ast.Ast.FunctionCall;
import com.example.logolsp.parser.ast.Ast.ListLit;
import com.example.logolsp.parser.ast.Ast.ParenExpr;
import com.example.logolsp.parser.ast.Ast.ProcedureDef;
import com.example.logolsp.parser.ast.Ast.Program;
import com.example.logolsp.parser.ast.Ast.Statement;
import com.example.logolsp.parser.ast.Ast.TopLevel;
import com.example.logolsp.parser.ast.Ast.UnaryOp;
import com.example.logolsp.parser.ast.Ast.WordRef;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.Optional;

/**
 * Serves {@code textDocument/hover} requests.
 *
 * <p>Walks the AST to find the token under the cursor, classifies it, and formats a
 * markdown payload:
 * <ul>
 *   <li>Built-in call → fenced code block with signature + arity, the doc string from
 *       {@code builtins.json}, and a "Turtle Academy built-in" footer.</li>
 *   <li>User procedure call or {@code TO}-name definition site → signature assembled
 *       from the {@link ProcedureDef}'s parameter tokens, "User-defined procedure"
 *       footer.</li>
 *   <li>Variable reference or parameter declaration → the kind label
 *       ({@code parameter}, {@code local}, {@code global}).</li>
 * </ul>
 *
 * <p>Numbers, strings, comments, operators, and punctuation produce {@code null} —
 * clients then show no hover tooltip.
 */
public final class HoverProvider {

    private HoverProvider() {}

    /** Returns a {@link Hover} or {@code null} if the position has no hoverable content. */
    public static Hover hover(ParsedDocument doc, LogoBuiltins builtins, Position pos) {
        Hit hit = findHit(doc.program(), pos);
        if (hit == null) return null;
        String markdown = switch (hit.role) {
            case VAR -> formatVariable(hit, doc.symbolTable());
            case CALL -> formatCallable(hit, doc.program(), doc.symbolTable(), builtins);
        };
        if (markdown == null) return null;
        return new Hover(new MarkupContent(MarkupKind.MARKDOWN, markdown), hit.token.range());
    }

    // --- AST walk ----------------------------------------------------------------

    private static Hit findHit(Program program, Position pos) {
        for (TopLevel item : program.items()) {
            if (!contains(item.range(), pos)) continue;
            if (item instanceof ProcedureDef def) {
                Hit h = inProcedureDef(def, pos);
                if (h != null) return h;
            } else if (item instanceof Statement stmt) {
                Hit h = inStatement(stmt, pos, null);
                if (h != null) return h;
            }
        }
        return null;
    }

    private static Hit inProcedureDef(ProcedureDef def, Position pos) {
        if (contains(def.nameToken().range(), pos)) {
            return new Hit(def.nameToken(), Role.CALL, def);
        }
        for (Token p : def.parameterTokens()) {
            if (contains(p.range(), pos)) return new Hit(p, Role.VAR, def);
        }
        for (Statement s : def.body()) {
            Hit h = inStatement(s, pos, def);
            if (h != null) return h;
        }
        return null;
    }

    private static Hit inStatement(Statement s, Position pos, ProcedureDef enclosing) {
        if (!(s instanceof Command cmd)) return null;
        if (contains(cmd.head().range(), pos)) return new Hit(cmd.head(), Role.CALL, enclosing);
        for (Expression arg : cmd.arguments()) {
            if (contains(arg.range(), pos)) {
                Hit h = inExpression(arg, pos, enclosing);
                if (h != null) return h;
            }
        }
        return null;
    }

    private static Hit inExpression(Expression e, Position pos, ProcedureDef enclosing) {
        if (e instanceof ColonVar cv) return new Hit(cv.token(), Role.VAR, enclosing);
        if (e instanceof WordRef wr) return new Hit(wr.token(), Role.CALL, enclosing);
        if (e instanceof FunctionCall fc) {
            if (contains(fc.head().range(), pos)) return new Hit(fc.head(), Role.CALL, enclosing);
            for (Expression a : fc.arguments()) {
                if (contains(a.range(), pos)) {
                    Hit h = inExpression(a, pos, enclosing);
                    if (h != null) return h;
                }
            }
            return null;
        }
        if (e instanceof BinaryOp bo) {
            if (contains(bo.left().range(), pos)) return inExpression(bo.left(), pos, enclosing);
            if (contains(bo.right().range(), pos)) return inExpression(bo.right(), pos, enclosing);
            return null;
        }
        if (e instanceof UnaryOp uo) {
            if (contains(uo.operand().range(), pos)) return inExpression(uo.operand(), pos, enclosing);
            return null;
        }
        if (e instanceof ParenExpr pe) {
            if (contains(pe.inner().range(), pos)) return inExpression(pe.inner(), pos, enclosing);
            return null;
        }
        if (e instanceof ListLit list) {
            for (Expression el : list.elements()) {
                if (contains(el.range(), pos)) {
                    Hit h = inExpression(el, pos, enclosing);
                    if (h != null) return h;
                }
            }
            return null;
        }
        return null; // NumberLit, QuoteWord: no hover
    }

    // --- formatters --------------------------------------------------------------

    private static String formatCallable(Hit hit, Program program, SymbolTable table, LogoBuiltins builtins) {
        String name = hit.token.lexeme();
        if (name.isEmpty()) return null;

        Optional<LogoBuiltins.Builtin> builtin = builtins.lookup(name);
        if (builtin.isPresent()) {
            LogoBuiltins.Builtin b = builtin.get();
            StringBuilder sb = new StringBuilder();
            sb.append("```logo\n")
              .append(b.canonicalName());
            if (!b.aliases().isEmpty()) {
                sb.append(" (aliases: ").append(String.join(", ", b.aliases())).append(")");
            }
            sb.append(" — arity ").append(b.arity()).append("\n```\n\n");
            sb.append(b.doc()).append("\n\n*Turtle Academy built-in*");
            return sb.toString();
        }

        Optional<Symbol> userProc = table.global().resolve(name)
                .filter(s -> s.kind() == Symbol.Kind.PROCEDURE);
        if (userProc.isPresent()) {
            Optional<ProcedureDef> def = findProcedureDef(program, userProc.get().name());
            String signature = def.map(HoverProvider::signatureOf).orElse("TO " + name);
            return "```logo\n" + signature + "\n```\n\n*User-defined procedure*";
        }

        return null;
    }

    private static String formatVariable(Hit hit, SymbolTable table) {
        Scope scope = hit.enclosing != null ? table.scopeOf(hit.enclosing) : table.global();
        String bare = stripSigil(hit.token.lexeme());
        if (bare.isEmpty()) return null;
        Optional<Symbol> sym = scope.resolve(bare);
        if (sym.isEmpty()) {
            return "```logo\n:" + bare + "\n```\n\n*Undefined*";
        }
        String kind = switch (sym.get().kind()) {
            case PARAMETER -> "parameter";
            case LOCAL -> "local";
            case GLOBAL -> "global";
            case PROCEDURE -> "procedure";
        };
        return "```logo\n:" + sym.get().name() + "\n```\n\n*(" + kind + ")*";
    }

    private static String signatureOf(ProcedureDef def) {
        StringBuilder sb = new StringBuilder("TO ").append(def.nameToken().lexeme());
        for (Token p : def.parameterTokens()) {
            sb.append(' ').append(p.lexeme());
        }
        return sb.toString();
    }

    private static Optional<ProcedureDef> findProcedureDef(Program program, String canonicalName) {
        for (TopLevel item : program.items()) {
            if (item instanceof ProcedureDef def
                    && def.nameToken().lexeme().equalsIgnoreCase(canonicalName)) {
                return Optional.of(def);
            }
        }
        return Optional.empty();
    }

    // --- helpers -----------------------------------------------------------------

    private static boolean contains(Range range, Position p) {
        int pl = p.getLine(), pc = p.getCharacter();
        Position s = range.getStart(), e = range.getEnd();
        if (pl < s.getLine() || pl > e.getLine()) return false;
        if (pl == s.getLine() && pc < s.getCharacter()) return false;
        if (pl == e.getLine() && pc > e.getCharacter()) return false;
        return true;
    }

    private static String stripSigil(String lexeme) {
        if (lexeme.isEmpty()) return lexeme;
        char c = lexeme.charAt(0);
        return (c == ':' || c == '"') ? lexeme.substring(1) : lexeme;
    }

    private record Hit(Token token, Role role, ProcedureDef enclosing) {}

    private enum Role { VAR, CALL }
}
