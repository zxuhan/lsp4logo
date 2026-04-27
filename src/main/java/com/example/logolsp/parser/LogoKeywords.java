package com.example.logolsp.parser;

import java.util.Locale;
import java.util.Set;

/**
 * The set of LOGO keywords — words that participate in syntax rather than acting as
 * callable primitives.
 *
 * <p>The list is deliberately tiny: only {@code TO} and {@code END}, which delimit
 * procedure definitions. Everything else commonly thought of as a "keyword" — {@code IF},
 * {@code IFELSE}, {@code REPEAT}, {@code MAKE}, {@code LOCAL}, {@code OUTPUT}, {@code STOP}
 * — is a built-in primitive (see {@code builtins.json}). The distinction matters because
 * primitives are callable in the same way as user procedures, so they want function-flavour
 * highlighting and completion, not keyword-flavour. Keeping this set small avoids the bug
 * where the keyword list and the builtins list disagree.
 *
 * <p>Match is case-insensitive (ADR-009).
 */
public final class LogoKeywords {

    private LogoKeywords() {}

    /** Canonical set, upper-case. */
    public static final Set<String> ALL = Set.of("TO", "END");

    /** Returns true iff {@code lexeme} matches a keyword case-insensitively. */
    public static boolean isKeyword(String lexeme) {
        if (lexeme == null || lexeme.isEmpty()) return false;
        return ALL.contains(lexeme.toUpperCase(Locale.ROOT));
    }
}
