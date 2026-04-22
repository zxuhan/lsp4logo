package com.example.logolsp.analysis;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A lexical scope.
 *
 * <p>Scopes form a tree rooted at a single {@link Kind#GLOBAL} instance. Each
 * {@link com.example.logolsp.parser.ast.Ast.ProcedureDef} owns one child
 * {@link Kind#PROCEDURE} scope whose symbols are its parameters, {@code LOCAL}s,
 * and any {@code MAKE}-introduced variables that resolve inside the chain.
 *
 * <p>Name resolution is case-insensitive per ADR-009.
 *
 * <p>Scopes are mutable during {@link Analyzer} construction and treated as immutable
 * once a {@link SymbolTable} is returned; none of the feature providers mutate them.
 */
public final class Scope {

    public enum Kind { GLOBAL, PROCEDURE }

    private final Scope parent;
    private final Kind kind;
    private final Map<String, Symbol> symbolsByName = new LinkedHashMap<>();

    private Scope(Scope parent, Kind kind) {
        this.parent = parent;
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** Creates a fresh top-level scope. */
    public static Scope global() {
        return new Scope(null, Kind.GLOBAL);
    }

    /** Creates a child scope linked to this one as parent. */
    public Scope newChild(Kind kind) {
        return new Scope(this, kind);
    }

    /** The parent scope, or {@code null} if this is the global scope. */
    public Scope parent() { return parent; }

    public Kind kind() { return kind; }

    /**
     * Declares a symbol in this scope.
     *
     * <p>If a symbol with the same (case-insensitive) name is already declared locally,
     * the existing symbol is returned unchanged. Callers check whether the returned
     * reference is the one they passed in to detect conflicts.
     */
    public Symbol declare(Symbol symbol) {
        Objects.requireNonNull(symbol, "symbol");
        String key = symbol.name().toUpperCase(Locale.ROOT);
        Symbol existing = symbolsByName.get(key);
        if (existing != null) return existing;
        symbolsByName.put(key, symbol);
        return symbol;
    }

    /** Returns a symbol declared directly in this scope, without walking to the parent. */
    public Optional<Symbol> lookupLocal(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(symbolsByName.get(name.toUpperCase(Locale.ROOT)));
    }

    /** Resolves a name by walking up the scope chain. */
    public Optional<Symbol> resolve(String name) {
        if (name == null) return Optional.empty();
        String key = name.toUpperCase(Locale.ROOT);
        for (Scope s = this; s != null; s = s.parent) {
            Symbol hit = s.symbolsByName.get(key);
            if (hit != null) return Optional.of(hit);
        }
        return Optional.empty();
    }

    /** Symbols declared directly in this scope, in declaration order. */
    public Collection<Symbol> localSymbols() {
        return Collections.unmodifiableCollection(symbolsByName.values());
    }
}
