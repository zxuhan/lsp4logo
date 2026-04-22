package com.example.logolsp.lexer;

import org.eclipse.lsp4j.Range;

import java.util.Objects;

/**
 * A single lexical token.
 *
 * <p>Tokens are immutable value objects. The {@code range} uses LSP4J's 0-based
 * line/character coordinate system so positions feed directly into LSP responses
 * (hover, definition, diagnostics, semantic tokens) without translation.
 *
 * @param type   the lexical category
 * @param lexeme the exact source text the token spans, including any leading sigil
 *               ({@code :}, {@code "}, {@code ;})
 * @param range  the half-open range [start, end) the lexeme occupies in the source
 */
public record Token(TokenType type, String lexeme, Range range) {
    public Token {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(lexeme, "lexeme");
        Objects.requireNonNull(range, "range");
    }
}
