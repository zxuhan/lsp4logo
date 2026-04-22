package com.example.logolsp.analysis;

import com.example.logolsp.lexer.Token;
import com.example.logolsp.lexer.TokenType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeTest {

    @Test
    void declare_then_lookupLocal_finds_the_symbol() {
        Scope s = Scope.global();
        Symbol foo = symbol("foo");
        assertThat(s.declare(foo)).isSameAs(foo);
        assertThat(s.lookupLocal("foo")).contains(foo);
    }

    @Test
    void lookup_is_case_insensitive() {
        Scope s = Scope.global();
        Symbol foo = symbol("foo");
        s.declare(foo);
        assertThat(s.lookupLocal("FOO")).contains(foo);
        assertThat(s.lookupLocal("Foo")).contains(foo);
    }

    @Test
    void declaring_a_duplicate_name_returns_the_original_and_does_not_overwrite() {
        Scope s = Scope.global();
        Symbol first = symbol("dup");
        Symbol second = symbol("DUP");
        s.declare(first);
        Symbol result = s.declare(second);
        assertThat(result).isSameAs(first);
        assertThat(s.localSymbols()).containsExactly(first);
    }

    @Test
    void resolve_walks_up_the_parent_chain() {
        Scope global = Scope.global();
        Scope inner = global.newChild(Scope.Kind.PROCEDURE);
        Symbol outer = symbol("outerName");
        global.declare(outer);
        assertThat(inner.resolve("outerName")).contains(outer);
    }

    @Test
    void resolve_prefers_the_innermost_binding() {
        Scope global = Scope.global();
        Scope inner = global.newChild(Scope.Kind.PROCEDURE);
        Symbol outer = symbol("x");
        Symbol shadow = symbol("x");
        global.declare(outer);
        inner.declare(shadow);
        assertThat(inner.resolve("x")).contains(shadow);
        assertThat(global.resolve("x")).contains(outer);
    }

    @Test
    void resolve_returns_empty_for_unknown_name() {
        Scope s = Scope.global();
        assertThat(s.resolve("nothere")).isEmpty();
        assertThat(s.resolve(null)).isEmpty();
    }

    @Test
    void parent_is_null_at_global_and_set_below() {
        Scope g = Scope.global();
        Scope inner = g.newChild(Scope.Kind.PROCEDURE);
        assertThat(g.parent()).isNull();
        assertThat(inner.parent()).isSameAs(g);
        assertThat(g.kind()).isEqualTo(Scope.Kind.GLOBAL);
        assertThat(inner.kind()).isEqualTo(Scope.Kind.PROCEDURE);
    }

    private static Symbol symbol(String name) {
        Token t = new Token(TokenType.WORD, name,
                new Range(new Position(0, 0), new Position(0, name.length())));
        return new Symbol(Symbol.Kind.PROCEDURE, name, t);
    }
}
