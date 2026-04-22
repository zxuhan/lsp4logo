package com.example.logolsp.lexer;

/**
 * Lexical category of a {@link Token}.
 *
 * <p>Keyword classification is deliberately absent from this enum — every bare identifier
 * comes out of the lexer as {@link #WORD}, and the parser decides which words act as
 * keywords. That keeps case-sensitivity (ADR-009) and keyword-set changes localised to
 * the parser without reshaping the token stream.
 */
public enum TokenType {
    /** A decimal or integer literal, e.g. {@code 42}, {@code 3.14}, {@code .5}. */
    NUMBER,
    /** A bare identifier, e.g. {@code FD}, {@code square}, {@code my_proc}. */
    WORD,
    /** A variable reference, e.g. {@code :x}. The lexeme includes the leading colon. */
    COLON_VAR,
    /** A quoted-word literal, e.g. {@code "hello}. The lexeme includes the leading quote. */
    QUOTE_WORD,

    LBRACKET, RBRACKET,
    LPAREN, RPAREN,

    PLUS, MINUS, STAR, SLASH,

    /** {@code =} */ EQ,
    /** {@code <} */ LT,
    /** {@code >} */ GT,
    /** {@code <=} */ LE,
    /** {@code >=} */ GE,
    /** {@code <>} */ NEQ,

    /** {@code ;...} to end of line. Lexeme includes the leading semicolon. */
    COMMENT,
    /** {@code \n} or {@code \r\n} (or lone {@code \r}). Significant as a statement separator. */
    NEWLINE,

    /** Unrecognised character. Lexeme is the single offending code point. */
    ERROR,
    /** End-of-input sentinel; always the final token in a scan. */
    EOF,
}
