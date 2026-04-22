package com.example.logolsp.lexer;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.logolsp.lexer.TokenType.COLON_VAR;
import static com.example.logolsp.lexer.TokenType.COMMENT;
import static com.example.logolsp.lexer.TokenType.EOF;
import static com.example.logolsp.lexer.TokenType.EQ;
import static com.example.logolsp.lexer.TokenType.ERROR;
import static com.example.logolsp.lexer.TokenType.GE;
import static com.example.logolsp.lexer.TokenType.GT;
import static com.example.logolsp.lexer.TokenType.LBRACKET;
import static com.example.logolsp.lexer.TokenType.LE;
import static com.example.logolsp.lexer.TokenType.LPAREN;
import static com.example.logolsp.lexer.TokenType.LT;
import static com.example.logolsp.lexer.TokenType.MINUS;
import static com.example.logolsp.lexer.TokenType.NEQ;
import static com.example.logolsp.lexer.TokenType.NEWLINE;
import static com.example.logolsp.lexer.TokenType.NUMBER;
import static com.example.logolsp.lexer.TokenType.PLUS;
import static com.example.logolsp.lexer.TokenType.QUOTE_WORD;
import static com.example.logolsp.lexer.TokenType.RBRACKET;
import static com.example.logolsp.lexer.TokenType.RPAREN;
import static com.example.logolsp.lexer.TokenType.SLASH;
import static com.example.logolsp.lexer.TokenType.STAR;
import static com.example.logolsp.lexer.TokenType.WORD;
import static org.assertj.core.api.Assertions.assertThat;

class LogoLexerTest {

    @Test
    void emits_EOF_on_empty_input() {
        List<Token> ts = tokens("");
        assertThat(ts).hasSize(1);
        assertThat(ts.get(0).type()).isEqualTo(EOF);
        assertThat(ts.get(0).range()).isEqualTo(range(0, 0, 0, 0));
    }

    @Test
    void tokenises_FD_100_with_precise_ranges() {
        List<Token> ts = tokens("FD 100");
        assertThat(types(ts)).containsExactly(WORD, NUMBER, EOF);
        assertThat(ts.get(0).lexeme()).isEqualTo("FD");
        assertThat(ts.get(0).range()).isEqualTo(range(0, 0, 0, 2));
        assertThat(ts.get(1).lexeme()).isEqualTo("100");
        assertThat(ts.get(1).range()).isEqualTo(range(0, 3, 0, 6));
    }

    @Test
    void colon_var_keeps_leading_colon_in_lexeme() {
        List<Token> ts = tokens(":size");
        assertThat(ts.get(0).type()).isEqualTo(COLON_VAR);
        assertThat(ts.get(0).lexeme()).isEqualTo(":size");
        assertThat(ts.get(0).range()).isEqualTo(range(0, 0, 0, 5));
    }

    @Test
    void quote_word_keeps_leading_quote_in_lexeme() {
        List<Token> ts = tokens("\"hello");
        assertThat(ts.get(0).type()).isEqualTo(QUOTE_WORD);
        assertThat(ts.get(0).lexeme()).isEqualTo("\"hello");
    }

    @Test
    void brackets_and_parens_are_distinct_tokens() {
        List<Token> ts = tokens("[ ( ) ]");
        assertThat(types(ts)).containsExactly(LBRACKET, LPAREN, RPAREN, RBRACKET, EOF);
    }

    @Test
    void arithmetic_operators() {
        List<Token> ts = tokens("1 + 2 * 3 / 4 - 5");
        assertThat(types(ts)).containsExactly(
                NUMBER, PLUS, NUMBER, STAR, NUMBER, SLASH, NUMBER, MINUS, NUMBER, EOF);
    }

    @Test
    void comparison_operators_including_multi_char_forms() {
        List<Token> ts = tokens(":x < 1 <= 2 > 3 >= 4 = 5 <> 6");
        assertThat(types(ts)).containsExactly(
                COLON_VAR, LT, NUMBER, LE, NUMBER, GT, NUMBER, GE, NUMBER,
                EQ, NUMBER, NEQ, NUMBER, EOF);
    }

    @Test
    void decimal_number_with_fractional_part() {
        List<Token> ts = tokens("3.14");
        assertThat(ts.get(0).type()).isEqualTo(NUMBER);
        assertThat(ts.get(0).lexeme()).isEqualTo("3.14");
    }

    @Test
    void decimal_number_with_leading_dot() {
        List<Token> ts = tokens(".5");
        assertThat(ts.get(0).type()).isEqualTo(NUMBER);
        assertThat(ts.get(0).lexeme()).isEqualTo(".5");
    }

    @Test
    void trailing_dot_is_not_part_of_number() {
        List<Token> ts = tokens("5.");
        assertThat(types(ts)).containsExactly(NUMBER, ERROR, EOF);
        assertThat(ts.get(0).lexeme()).isEqualTo("5");
        assertThat(ts.get(1).lexeme()).isEqualTo(".");
    }

    @Test
    void comment_spans_to_end_of_line_and_NEWLINE_is_emitted_separately() {
        List<Token> ts = tokens("FD ; go forward\nBK");
        assertThat(types(ts)).containsExactly(WORD, COMMENT, NEWLINE, WORD, EOF);
        assertThat(ts.get(1).lexeme()).isEqualTo("; go forward");
    }

    @Test
    void comment_at_eof_has_no_following_newline() {
        List<Token> ts = tokens("; only");
        assertThat(types(ts)).containsExactly(COMMENT, EOF);
        assertThat(ts.get(0).lexeme()).isEqualTo("; only");
    }

    @Test
    void newline_lf_spans_one_line_break() {
        List<Token> ts = tokens("a\nb");
        assertThat(types(ts)).containsExactly(WORD, NEWLINE, WORD, EOF);
        assertThat(ts.get(1).range()).isEqualTo(range(0, 1, 1, 0));
        assertThat(ts.get(2).range()).isEqualTo(range(1, 0, 1, 1));
    }

    @Test
    void newline_crlf_is_one_token_and_advances_line() {
        List<Token> ts = tokens("a\r\nb");
        assertThat(types(ts)).containsExactly(WORD, NEWLINE, WORD, EOF);
        assertThat(ts.get(1).lexeme()).isEqualTo("\r\n");
        assertThat(ts.get(2).range()).isEqualTo(range(1, 0, 1, 1));
    }

    @Test
    void unknown_character_produces_ERROR_and_does_not_stall_the_stream() {
        List<Token> ts = tokens("FD @ 100");
        assertThat(types(ts)).containsExactly(WORD, ERROR, NUMBER, EOF);
        assertThat(ts.get(1).lexeme()).isEqualTo("@");
        assertThat(ts.get(1).range()).isEqualTo(range(0, 3, 0, 4));
    }

    @Test
    void word_can_contain_digits_and_underscores_after_first_char() {
        List<Token> ts = tokens("heading_1");
        assertThat(ts.get(0).type()).isEqualTo(WORD);
        assertThat(ts.get(0).lexeme()).isEqualTo("heading_1");
    }

    @Test
    void full_program_with_TO_REPEAT_and_END() {
        String src = "TO square :size\nREPEAT 4 [ FD :size RT 90 ]\nEND";
        List<Token> ts = tokens(src);
        assertThat(types(ts)).containsExactly(
                WORD, WORD, COLON_VAR, NEWLINE,                 // TO square :size
                WORD, NUMBER, LBRACKET, WORD, COLON_VAR,        // REPEAT 4 [ FD :size
                WORD, NUMBER, RBRACKET, NEWLINE,                // RT 90 ]
                WORD,                                           // END
                EOF);
        assertThat(ts.get(0).lexeme()).isEqualTo("TO");
        assertThat(ts.get(1).lexeme()).isEqualTo("square");
        assertThat(ts.get(ts.size() - 2).lexeme()).isEqualTo("END");
    }

    @Test
    void consecutive_newlines_produce_one_token_each() {
        List<Token> ts = tokens("a\n\nb");
        assertThat(types(ts)).containsExactly(WORD, NEWLINE, NEWLINE, WORD, EOF);
    }

    @Test
    void tabs_are_skipped_like_spaces() {
        List<Token> ts = tokens("FD\t100");
        assertThat(types(ts)).containsExactly(WORD, NUMBER, EOF);
        assertThat(ts.get(1).range()).isEqualTo(range(0, 3, 0, 6));
    }

    // --- helpers ----------------------------------------------------------------------

    private static List<Token> tokens(String source) {
        return new LogoLexer(source).tokenize();
    }

    private static TokenType[] types(List<Token> ts) {
        return ts.stream().map(Token::type).toArray(TokenType[]::new);
    }

    private static Range range(int startLine, int startChar, int endLine, int endChar) {
        return new Range(new Position(startLine, startChar), new Position(endLine, endChar));
    }
}
