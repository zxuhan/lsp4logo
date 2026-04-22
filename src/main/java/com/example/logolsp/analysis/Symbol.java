package com.example.logolsp.analysis;

import com.example.logolsp.lexer.Token;
import org.eclipse.lsp4j.Range;

import java.util.Objects;

/**
 * A named binding resolvable inside a {@link Scope}.
 *
 * <p>{@link #defToken()} is the lexeme that introduced the symbol (the procedure-name
 * token for {@link Kind#PROCEDURE}, the {@code :param} token for {@link Kind#PARAMETER},
 * the {@code "name} quoted word for {@link Kind#LOCAL} or {@link Kind#GLOBAL}). Its
 * range is where go-to-definition lands.
 *
 * @param kind     the symbol category
 * @param name     the name without any sigil ({@code :} or {@code "}), case-preserved
 * @param defToken the token that introduced the binding
 */
public record Symbol(Kind kind, String name, Token defToken) {

    public Symbol {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(defToken, "defToken");
    }

    /** The range of the defining token — the go-to-definition target. */
    public Range defRange() {
        return defToken.range();
    }

    public enum Kind {
        /** Defined by {@code TO <name> … END}. */
        PROCEDURE,
        /** Declared as {@code :param} in a {@code TO} header. */
        PARAMETER,
        /** Introduced by {@code LOCAL "name}. */
        LOCAL,
        /** Introduced by a top-level {@code MAKE "name …}, or by a {@code MAKE}
         *  inside a procedure whose target is not visible in the enclosing scope chain. */
        GLOBAL,
    }
}
