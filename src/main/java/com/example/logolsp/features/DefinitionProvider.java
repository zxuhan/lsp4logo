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
import com.example.logolsp.util.Names;
import com.example.logolsp.util.Ranges;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

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
        return definition(doc, pos, NOOP);
    }

    /** Same as {@link #definition(ParsedDocument, Position)}, but cooperates with cancellation. */
    public static List<Location> definition(ParsedDocument doc, Position pos, CancelChecker checker) {
        Hit hit = findHit(doc.program(), pos, checker);
        if (hit == null) return List.of();
        checker.checkCanceled();
        return resolve(hit, doc.symbolTable(), doc.uri());
    }

    // --- AST walk ----------------------------------------------------------------

    private static Hit findHit(Program program, Position pos, CancelChecker checker) {
        for (TopLevel item : program.items()) {
            checker.checkCanceled();
            if (!Ranges.contains(item.range(), pos)) continue;
            if (item instanceof ProcedureDef def) {
                Hit h = inProcedureDef(def, pos, checker);
                if (h != null) return h;
            } else if (item instanceof Statement stmt) {
                Hit h = inStatement(stmt, pos, null, checker);
                if (h != null) return h;
            }
        }
        return null;
    }

    private static Hit inProcedureDef(ProcedureDef def, Position pos, CancelChecker checker) {
        if (Ranges.contains(def.nameToken().range(), pos)) {
            return new Hit(def.nameToken(), Kind.PROC_NAME_DEF, def);
        }
        for (Token p : def.parameterTokens()) {
            if (Ranges.contains(p.range(), pos)) {
                return new Hit(p, Kind.PARAM_DEF, def);
            }
        }
        for (Statement s : def.body()) {
            Hit h = inStatement(s, pos, def, checker);
            if (h != null) return h;
        }
        return null;
    }

    private static Hit inStatement(Statement s, Position pos, ProcedureDef enclosing, CancelChecker checker) {
        if (!(s instanceof Command cmd)) return null;
        if (Ranges.contains(cmd.head().range(), pos)) {
            return new Hit(cmd.head(), Kind.PROC_CALL, enclosing);
        }
        for (Expression arg : cmd.arguments()) {
            if (Ranges.contains(arg.range(), pos)) {
                Hit h = inExpression(arg, pos, enclosing, checker);
                if (h != null) return h;
            }
        }
        return null;
    }

    private static Hit inExpression(Expression e, Position pos, ProcedureDef enclosing, CancelChecker checker) {
        checker.checkCanceled();
        if (e instanceof ColonVar cv) {
            return new Hit(cv.token(), Kind.VAR_REF, enclosing);
        }
        if (e instanceof WordRef wr) {
            return new Hit(wr.token(), Kind.PROC_CALL, enclosing);
        }
        if (e instanceof FunctionCall fc) {
            if (Ranges.contains(fc.head().range(), pos)) {
                return new Hit(fc.head(), Kind.PROC_CALL, enclosing);
            }
            for (Expression a : fc.arguments()) {
                if (Ranges.contains(a.range(), pos)) {
                    Hit h = inExpression(a, pos, enclosing, checker);
                    if (h != null) return h;
                }
            }
            return null;
        }
        if (e instanceof BinaryOp bo) {
            if (Ranges.contains(bo.left().range(), pos)) return inExpression(bo.left(), pos, enclosing, checker);
            if (Ranges.contains(bo.right().range(), pos)) return inExpression(bo.right(), pos, enclosing, checker);
            return null;
        }
        if (e instanceof UnaryOp uo) {
            if (Ranges.contains(uo.operand().range(), pos)) return inExpression(uo.operand(), pos, enclosing, checker);
            return null;
        }
        if (e instanceof ParenExpr pe) {
            if (Ranges.contains(pe.inner().range(), pos)) return inExpression(pe.inner(), pos, enclosing, checker);
            return null;
        }
        if (e instanceof ListLit list) {
            for (Expression el : list.elements()) {
                if (Ranges.contains(el.range(), pos)) {
                    Hit h = inExpression(el, pos, enclosing, checker);
                    if (h != null) return h;
                }
            }
            return null;
        }
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
                String name = Names.stripSigil(hit.token.lexeme());
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

    private record Hit(Token token, Kind kind, ProcedureDef enclosing) {}

    private enum Kind {
        PROC_NAME_DEF, PARAM_DEF, VAR_REF, PROC_CALL,
    }

    private static final CancelChecker NOOP = () -> {};
}
