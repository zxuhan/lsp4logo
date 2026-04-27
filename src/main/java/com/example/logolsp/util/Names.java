package com.example.logolsp.util;

/**
 * Helpers for LOGO identifier lexemes.
 *
 * <p>Variable references and quoted-word literals carry a leading sigil ({@code :} or
 * {@code "}). Symbols are stored without it, so resolution and display strip the sigil
 * at the lexeme/symbol boundary.
 */
public final class Names {

    private Names() {}

    /** Strips a leading {@code :} or {@code "} sigil from a lexeme; otherwise returns it unchanged. */
    public static String stripSigil(String lexeme) {
        if (lexeme == null || lexeme.isEmpty()) return lexeme;
        char c = lexeme.charAt(0);
        return (c == ':' || c == '"') ? lexeme.substring(1) : lexeme;
    }
}
