package com.example.logolsp.analysis;

import com.example.logolsp.builtins.LogoBuiltins;
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
import com.example.logolsp.parser.ast.Ast.QuoteWord;
import com.example.logolsp.parser.ast.Ast.Statement;
import com.example.logolsp.parser.ast.Ast.TopLevel;
import com.example.logolsp.parser.ast.Ast.UnaryOp;
import com.example.logolsp.parser.ast.Ast.WordRef;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Walks a parsed {@link Program}, populates a scope tree, and emits semantic
 * diagnostics.
 *
 * <h2>Scoping model</h2>
 * <ul>
 *   <li><b>Global scope</b> receives every {@code TO <name> … END} definition
 *       ({@link Symbol.Kind#PROCEDURE}) and every top-level {@code MAKE "x …}
 *       ({@link Symbol.Kind#GLOBAL}).</li>
 *   <li><b>Procedure scope</b> receives parameters ({@link Symbol.Kind#PARAMETER})
 *       and names introduced by {@code LOCAL "x} ({@link Symbol.Kind#LOCAL}). A
 *       {@code MAKE "x …} inside a procedure body is treated as a local if no
 *       visible binding is already in scope; otherwise it is assumed to rebind the
 *       existing one.</li>
 * </ul>
 *
 * <p>LOGO is dynamically scoped at runtime, but for LSP navigation purposes this
 * lexical approximation is strictly more useful: jumping from {@code :x} inside a
 * procedure to the nearest enclosing declaration is what a human expects. ADR-007
 * records the trade-off.
 *
 * <h2>Diagnostics emitted</h2>
 * <ul>
 *   <li>{@code duplicate procedure definition: <name>}</li>
 *   <li>{@code duplicate parameter: <name>}</li>
 *   <li>{@code unknown procedure: <name>}</li>
 *   <li>{@code undefined variable: :<name>}</li>
 * </ul>
 */
public final class Analyzer {

    private static final String DIAGNOSTIC_SOURCE = "logo-lsp";

    private final Program program;
    private final LogoBuiltins builtins;
    private final Scope global = Scope.global();
    private final Map<ProcedureDef, Scope> procedureScopes = new IdentityHashMap<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private Analyzer(Program program, LogoBuiltins builtins) {
        this.program = Objects.requireNonNull(program, "program");
        this.builtins = Objects.requireNonNull(builtins, "builtins");
    }

    /** Analyses the program and returns the resulting {@link SymbolTable}. */
    public static SymbolTable analyze(Program program, LogoBuiltins builtins) {
        Analyzer a = new Analyzer(program, builtins);
        a.run();
        return new SymbolTable(a.global, a.procedureScopes, a.diagnostics);
    }

    private void run() {
        declareProceduresGlobally();
        buildProcedureScopes();
        collectTopLevelMakes();
        validateReferences();
    }

    // --- declaration passes -------------------------------------------------------

    private void declareProceduresGlobally() {
        for (TopLevel item : program.items()) {
            if (!(item instanceof ProcedureDef def)) continue;
            String name = def.nameToken().lexeme();
            if (name.isEmpty()) continue; // parser recovered from missing name
            Optional<Symbol> existing = global.lookupLocal(name);
            if (existing.isPresent() && existing.get().kind() == Symbol.Kind.PROCEDURE) {
                error(def.nameToken().range(), "duplicate procedure definition: " + name);
                continue;
            }
            Symbol s = new Symbol(Symbol.Kind.PROCEDURE, name, def.nameToken());
            global.declare(s);
        }
    }

    private void buildProcedureScopes() {
        for (TopLevel item : program.items()) {
            if (!(item instanceof ProcedureDef def)) continue;
            Scope procScope = global.newChild(Scope.Kind.PROCEDURE);
            procedureScopes.put(def, procScope);
            for (Token param : def.parameterTokens()) {
                String name = stripSigil(param.lexeme());
                Symbol candidate = new Symbol(Symbol.Kind.PARAMETER, name, param);
                Symbol declared = procScope.declare(candidate);
                if (declared != candidate) {
                    error(param.range(), "duplicate parameter: " + name);
                }
            }
            for (Statement s : def.body()) {
                collectDeclarations(s, procScope);
            }
        }
    }

    private void collectTopLevelMakes() {
        for (TopLevel item : program.items()) {
            if (item instanceof Statement stmt) {
                collectDeclarations(stmt, global);
            }
        }
    }

    private void collectDeclarations(Statement s, Scope scope) {
        if (!(s instanceof Command cmd)) return;
        processDeclarationCommand(cmd.head(), cmd.arguments(), scope);
        for (Expression arg : cmd.arguments()) {
            walkExpressionForDeclarations(arg, scope);
        }
    }

    private void processDeclarationCommand(Token head, List<Expression> args, Scope scope) {
        String upper = head.lexeme().toUpperCase(Locale.ROOT);
        if ("LOCAL".equals(upper) && !args.isEmpty() && args.get(0) instanceof QuoteWord qw) {
            declareLocal(qw.token(), scope);
        } else if ("MAKE".equals(upper) && !args.isEmpty() && args.get(0) instanceof QuoteWord qw) {
            declareMakeTarget(qw.token(), scope);
        }
    }

    private void walkExpressionForDeclarations(Expression expr, Scope scope) {
        if (expr instanceof ListLit list) {
            for (Expression e : list.elements()) {
                walkExpressionForDeclarations(e, scope);
            }
        } else if (expr instanceof FunctionCall fc) {
            processDeclarationCommand(fc.head(), fc.arguments(), scope);
            for (Expression a : fc.arguments()) {
                walkExpressionForDeclarations(a, scope);
            }
        } else if (expr instanceof BinaryOp bo) {
            walkExpressionForDeclarations(bo.left(), scope);
            walkExpressionForDeclarations(bo.right(), scope);
        } else if (expr instanceof UnaryOp uo) {
            walkExpressionForDeclarations(uo.operand(), scope);
        } else if (expr instanceof ParenExpr pe) {
            walkExpressionForDeclarations(pe.inner(), scope);
        }
    }

    private void declareLocal(Token qwToken, Scope scope) {
        String name = stripSigil(qwToken.lexeme());
        if (name.isEmpty()) return;
        scope.declare(new Symbol(Symbol.Kind.LOCAL, name, qwToken));
    }

    private void declareMakeTarget(Token qwToken, Scope scope) {
        String name = stripSigil(qwToken.lexeme());
        if (name.isEmpty()) return;
        if (scope.resolve(name).isPresent()) return; // rebinding an existing binding
        global.declare(new Symbol(Symbol.Kind.GLOBAL, name, qwToken));
    }

    // --- validation pass ----------------------------------------------------------

    private void validateReferences() {
        for (TopLevel item : program.items()) {
            if (item instanceof ProcedureDef def) {
                Scope scope = procedureScopes.get(def);
                for (Statement s : def.body()) {
                    validateStatement(s, scope);
                }
            } else if (item instanceof Statement stmt) {
                validateStatement(stmt, global);
            }
        }
    }

    private void validateStatement(Statement s, Scope scope) {
        if (!(s instanceof Command cmd)) return;
        validateHead(cmd.head());
        for (Expression arg : cmd.arguments()) {
            validateExpression(arg, scope);
        }
    }

    private void validateHead(Token head) {
        if (isKnownCallable(head.lexeme())) return;
        error(head.range(), "unknown procedure: " + head.lexeme());
    }

    private void validateExpression(Expression e, Scope scope) {
        if (e instanceof ColonVar cv) {
            String name = stripSigil(cv.token().lexeme());
            if (name.isEmpty()) return;
            if (scope.resolve(name).isEmpty()) {
                error(cv.token().range(), "undefined variable: " + cv.token().lexeme());
            }
        } else if (e instanceof WordRef wr) {
            if (!isKnownCallable(wr.token().lexeme())) {
                error(wr.token().range(), "unknown procedure: " + wr.token().lexeme());
            }
        } else if (e instanceof FunctionCall fc) {
            if (!isKnownCallable(fc.head().lexeme())) {
                error(fc.head().range(), "unknown procedure: " + fc.head().lexeme());
            }
            for (Expression a : fc.arguments()) {
                validateExpression(a, scope);
            }
        } else if (e instanceof BinaryOp bo) {
            validateExpression(bo.left(), scope);
            validateExpression(bo.right(), scope);
        } else if (e instanceof UnaryOp uo) {
            validateExpression(uo.operand(), scope);
        } else if (e instanceof ParenExpr pe) {
            validateExpression(pe.inner(), scope);
        } else if (e instanceof ListLit list) {
            for (Expression el : list.elements()) {
                validateExpression(el, scope);
            }
        }
        // NumberLit, QuoteWord: no references to check
    }

    private boolean isKnownCallable(String name) {
        if (builtins.lookup(name).isPresent()) return true;
        return global.resolve(name)
                .filter(s -> s.kind() == Symbol.Kind.PROCEDURE)
                .isPresent();
    }

    // --- utilities ----------------------------------------------------------------

    private static String stripSigil(String lexeme) {
        if (lexeme.isEmpty()) return lexeme;
        char c = lexeme.charAt(0);
        return (c == ':' || c == '"') ? lexeme.substring(1) : lexeme;
    }

    private void error(Range range, String message) {
        Diagnostic d = new Diagnostic(range, message);
        d.setSeverity(DiagnosticSeverity.Error);
        d.setSource(DIAGNOSTIC_SOURCE);
        diagnostics.add(d);
    }
}
