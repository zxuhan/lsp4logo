package com.example.logolsp.lexer;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Hand-written lexer for LOGO source text.
 *
 * <p>The lexer is deliberately total — it never throws. Unrecognised characters produce
 * a {@link TokenType#ERROR} token carrying the bad character's range, so diagnostics and
 * downstream features can continue operating on half-written source during live editing.
 *
 * <p>Every {@link Token} carries an LSP {@link Range} in 0-based line/character
 * coordinates, computed on the fly as the lexer walks the source. LF, CRLF, and lone CR
 * are all tokenised as a single {@link TokenType#NEWLINE}; only LF (and the LF half of
 * CRLF) advances the line counter, so files using lone CR line terminators will have
 * slightly off position reports. Modern Unix/Windows files are handled correctly.
 *
 * <p>The lexer does <em>not</em> classify keywords. Every bare identifier is emitted as
 * {@link TokenType#WORD}; the parser owns the keyword set and the case-sensitivity rule.
 */
public final class LogoLexer {

    private final String source;
    private int pos;
    private int line;
    private int column;

    private int tokenStartLine;
    private int tokenStartColumn;

    public LogoLexer(String source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Tokenises the full source. Always terminates with exactly one {@link TokenType#EOF}. */
    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (!isAtEnd()) {
            skipInlineWhitespace();
            if (isAtEnd()) break;
            beginToken();
            tokens.add(scanToken());
        }
        beginToken();
        tokens.add(finish(TokenType.EOF, ""));
        return tokens;
    }

    // --- scanning ---------------------------------------------------------------------

    private Token scanToken() {
        char c = peek();
        return switch (c) {
            case '\r' -> scanCrNewline();
            case '\n' -> { advance(); yield finish(TokenType.NEWLINE, "\n"); }
            case '[' -> { advance(); yield finish(TokenType.LBRACKET, "["); }
            case ']' -> { advance(); yield finish(TokenType.RBRACKET, "]"); }
            case '(' -> { advance(); yield finish(TokenType.LPAREN, "("); }
            case ')' -> { advance(); yield finish(TokenType.RPAREN, ")"); }
            case '+' -> { advance(); yield finish(TokenType.PLUS, "+"); }
            case '-' -> { advance(); yield finish(TokenType.MINUS, "-"); }
            case '*' -> { advance(); yield finish(TokenType.STAR, "*"); }
            case '/' -> { advance(); yield finish(TokenType.SLASH, "/"); }
            case '=' -> { advance(); yield finish(TokenType.EQ, "="); }
            case '<' -> scanLess();
            case '>' -> scanGreater();
            case ';' -> scanComment();
            case '"' -> scanQuoteWord();
            case ':' -> scanColonVar();
            default -> {
                if (isDigit(c) || startsDecimal(c)) {
                    yield scanNumber();
                }
                if (isWordStart(c)) {
                    yield scanWord();
                }
                advance();
                yield finish(TokenType.ERROR, String.valueOf(c));
            }
        };
    }

    private Token scanCrNewline() {
        StringBuilder lexeme = new StringBuilder();
        lexeme.append(peek());
        advance(); // \r
        if (!isAtEnd() && peek() == '\n') {
            lexeme.append('\n');
            advance();
        }
        return finish(TokenType.NEWLINE, lexeme.toString());
    }

    private Token scanLess() {
        advance();
        if (!isAtEnd()) {
            char next = peek();
            if (next == '=') { advance(); return finish(TokenType.LE, "<="); }
            if (next == '>') { advance(); return finish(TokenType.NEQ, "<>"); }
        }
        return finish(TokenType.LT, "<");
    }

    private Token scanGreater() {
        advance();
        if (!isAtEnd() && peek() == '=') {
            advance();
            return finish(TokenType.GE, ">=");
        }
        return finish(TokenType.GT, ">");
    }

    private Token scanComment() {
        int start = pos;
        while (!isAtEnd() && peek() != '\n' && peek() != '\r') advance();
        return finish(TokenType.COMMENT, source.substring(start, pos));
    }

    private Token scanQuoteWord() {
        int start = pos;
        advance(); // "
        while (!isAtEnd() && isWordPart(peek())) advance();
        return finish(TokenType.QUOTE_WORD, source.substring(start, pos));
    }

    private Token scanColonVar() {
        int start = pos;
        advance(); // :
        while (!isAtEnd() && isWordPart(peek())) advance();
        return finish(TokenType.COLON_VAR, source.substring(start, pos));
    }

    private Token scanNumber() {
        int start = pos;
        while (!isAtEnd() && isDigit(peek())) advance();
        if (!isAtEnd() && peek() == '.' && pos + 1 < source.length() && isDigit(source.charAt(pos + 1))) {
            advance(); // .
            while (!isAtEnd() && isDigit(peek())) advance();
        }
        return finish(TokenType.NUMBER, source.substring(start, pos));
    }

    private Token scanWord() {
        int start = pos;
        advance(); // first char, already verified as isWordStart
        while (!isAtEnd() && isWordPart(peek())) advance();
        return finish(TokenType.WORD, source.substring(start, pos));
    }

    // --- position bookkeeping ---------------------------------------------------------

    private void skipInlineWhitespace() {
        while (!isAtEnd()) {
            char c = peek();
            if (c == ' ' || c == '\t') {
                advance();
            } else {
                return;
            }
        }
    }

    private void beginToken() {
        tokenStartLine = line;
        tokenStartColumn = column;
    }

    private Token finish(TokenType type, String lexeme) {
        Range range = new Range(
                new Position(tokenStartLine, tokenStartColumn),
                new Position(line, column));
        return new Token(type, lexeme, range);
    }

    private char peek() {
        return source.charAt(pos);
    }

    private void advance() {
        char c = source.charAt(pos);
        pos++;
        if (c == '\n') {
            line++;
            column = 0;
        } else {
            column++;
        }
    }

    private boolean isAtEnd() {
        return pos >= source.length();
    }

    private boolean startsDecimal(char c) {
        return c == '.' && pos + 1 < source.length() && isDigit(source.charAt(pos + 1));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isWordStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isWordPart(char c) {
        return isWordStart(c) || isDigit(c);
    }
}
