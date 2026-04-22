package com.example.logolsp.analysis;

import com.example.logolsp.parser.ast.Ast.ProcedureDef;
import org.eclipse.lsp4j.Diagnostic;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only result of an {@link Analyzer} run: the scope tree and any semantic
 * diagnostics.
 *
 * <p>The map keyed by {@link ProcedureDef} is keyed by identity rather than value so
 * that distinct {@code TO foo … END} definitions with the same name and body
 * (unusual but legal) don't collide.
 */
public final class SymbolTable {

    private final Scope global;
    private final Map<ProcedureDef, Scope> procedureScopes;
    private final List<Diagnostic> diagnostics;

    SymbolTable(Scope global,
                Map<ProcedureDef, Scope> procedureScopes,
                List<Diagnostic> diagnostics) {
        this.global = Objects.requireNonNull(global, "global");
        Map<ProcedureDef, Scope> copy = new IdentityHashMap<>(procedureScopes);
        this.procedureScopes = Collections.unmodifiableMap(copy);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public Scope global() { return global; }

    /** Returns the procedure-local scope, or the global scope if the def wasn't analysed. */
    public Scope scopeOf(ProcedureDef def) {
        Scope s = procedureScopes.get(def);
        return s != null ? s : global;
    }

    public List<Diagnostic> diagnostics() { return diagnostics; }

    public Map<ProcedureDef, Scope> procedureScopes() { return procedureScopes; }
}
