package com.example.logolsp.features;

import com.example.logolsp.analysis.Scope;
import com.example.logolsp.analysis.Symbol;
import com.example.logolsp.analysis.SymbolTable;
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
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.List;
import java.util.Optional;

/**
 * Resolves {@code textDocument/definition} requests.
 *
 * <p>Given a position, walks the AST to find the innermost reference token, then
 * resolves it via the scope chain:
 * <ul>
 *   <li>{@code :var} → walk the enclosing procedure scope up to global.</li>
 *   <li>Command / function-call head ({@code FD}, {@code square}) → user-defined
 *       {@code TO} procedure in the global scope, if any.</li>
 *   <li>Built-ins and unresolved names → empty (no location).</li>
 *   <li>Clicking on a definition site itself ({@code TO foo}'s name, a parameter
 *       {@code :x}) → self-location, which most LSP clients render as a no-op jump.</li>
 * </ul>
 *
 * <p>Stateless: all state is passed in through parameters, so the provider is safe to
 * invoke from multiple LSP worker threads concurrently.
 */
public final class DefinitionProvider {

    private DefinitionProvider() {}

    /**
     * Returns zero or one {@link Location} values — zero if the position is on
     * whitespace, a builtin, or an unresolved name; exactly one for a successful
     * resolution.
     */
    public static List<Location> definition(ParsedDocument doc, Position pos) {
        Hit hit = findHit(doc.program(), pos);
        if (hit == null) return List.of();
        return resolve(hit, doc.symbolTable(), doc.uri());
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
            return new Hit(def.nameToken(), Kind.PROC_NAME_DEF, def);
        }
        for (Token p : def.parameterTokens()) {
            if (contains(p.range(), pos)) {
                return new Hit(p, Kind.PARAM_DEF, def);
            }
        }
        for (Statement s : def.body()) {
            Hit h = inStatement(s, pos, def);
            if (h != null) return h;
        }
        return null;
    }

    private static Hit inStatement(Statement s, Position pos, ProcedureDef enclosing) {
        if (!(s instanceof Command cmd)) return null;
        if (contains(cmd.head().range(), pos)) {
            return new Hit(cmd.head(), Kind.PROC_CALL, enclosing);
        }
        for (Expression arg : cmd.arguments()) {
            if (contains(arg.range(), pos)) {
                Hit h = inExpression(arg, pos, enclosing);
                if (h != null) return h;
            }
        }
        return null;
    }

    private static Hit inExpression(Expression e, Position pos, ProcedureDef enclosing) {
        if (e instanceof ColonVar cv) {
            return new Hit(cv.token(), Kind.VAR_REF, enclosing);
        }
        if (e instanceof WordRef wr) {
            return new Hit(wr.token(), Kind.PROC_CALL, enclosing);
        }
        if (e instanceof FunctionCall fc) {
            if (contains(fc.head().range(), pos)) {
                return new Hit(fc.head(), Kind.PROC_CALL, enclosing);
            }
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
        // NumberLit, QuoteWord: not click-resolvable in Phase 5.
        return null;
    }

    // --- resolution --------------------------------------------------------------

    private static List<Location> resolve(Hit hit, SymbolTable symbolTable, String uri) {
        switch (hit.kind) {
            case PROC_NAME_DEF, PARAM_DEF -> {
                return List.of(new Location(uri, hit.token.range()));
            }
            case VAR_REF -> {
                Scope scope = hit.enclosing != null
                        ? symbolTable.scopeOf(hit.enclosing)
                        : symbolTable.global();
                String name = stripSigil(hit.token.lexeme());
                return scope.resolve(name)
                        .map(s -> List.of(new Location(uri, s.defRange())))
                        .orElse(List.of());
            }
            case PROC_CALL -> {
                Optional<Symbol> proc = symbolTable.global().resolve(hit.token.lexeme())
                        .filter(s -> s.kind() == Symbol.Kind.PROCEDURE);
                return proc.map(s -> List.of(new Location(uri, s.defRange())))
                        .orElse(List.of());
            }
        }
        return List.of();
    }

    // --- helpers -----------------------------------------------------------------

    private static boolean contains(Range range, Position pos) {
        if (range == null || pos == null) return false;
        int pl = pos.getLine(), pc = pos.getCharacter();
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

    private record Hit(Token token, Kind kind, ProcedureDef enclosing) {}

    private enum Kind {
        PROC_NAME_DEF, PARAM_DEF, VAR_REF, PROC_CALL,
    }
}
