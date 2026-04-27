package com.example.logolsp.parser;

import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.lexer.LogoLexer;
import com.example.logolsp.lexer.Token;
import com.example.logolsp.parser.ast.Ast.BinaryOp;
import com.example.logolsp.parser.ast.Ast.ColonVar;
import com.example.logolsp.parser.ast.Ast.Command;
import com.example.logolsp.parser.ast.Ast.Expression;
import com.example.logolsp.parser.ast.Ast.FunctionCall;
import com.example.logolsp.parser.ast.Ast.ListLit;
import com.example.logolsp.parser.ast.Ast.NumberLit;
import com.example.logolsp.parser.ast.Ast.ProcedureDef;
import com.example.logolsp.parser.ast.Ast.Program;
import com.example.logolsp.parser.ast.Ast.QuoteWord;
import com.example.logolsp.parser.ast.Ast.Statement;
import com.example.logolsp.parser.ast.Ast.TopLevel;
import com.example.logolsp.parser.ast.Ast.UnaryOp;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class LogoParserTest {

    private static final LogoBuiltins BUILTINS = LogoBuiltins.loadDefault();

    @Test
    void empty_source_yields_an_empty_program_with_no_diagnostics() {
        ParseResult r = parse("");
        assertThat(r.program().items()).isEmpty();
        assertThat(r.diagnostics()).isEmpty();
    }

    @Test
    void single_builtin_command_consumes_exactly_its_arity() {
        ParseResult r = parse("FD 100");
        assertThat(r.diagnostics()).isEmpty();
        assertThat(r.program().items()).hasSize(1);
        Command cmd = (Command) r.program().items().get(0);
        assertThat(cmd.head().lexeme()).isEqualTo("FD");
        assertThat(cmd.arguments()).hasSize(1);
        assertThat(cmd.arguments().get(0)).isInstanceOf(NumberLit.class);
        assertThat(((NumberLit) cmd.arguments().get(0)).token().lexeme()).isEqualTo("100");
    }

    @Test
    void procedure_definition_captures_name_parameters_and_body() {
        ParseResult r = parse("TO square :size\n  FD :size\nEND\n");
        assertThat(r.diagnostics()).isEmpty();
        assertThat(r.program().items()).hasSize(1);
        ProcedureDef def = (ProcedureDef) r.program().items().get(0);
        assertThat(def.nameToken().lexeme()).isEqualTo("square");
        assertThat(def.parameterTokens()).hasSize(1);
        assertThat(def.parameterTokens().get(0).lexeme()).isEqualTo(":size");
        assertThat(def.body()).hasSize(1);
        Command body = (Command) def.body().get(0);
        assertThat(body.head().lexeme()).isEqualTo("FD");
        assertThat(body.arguments()).hasSize(1);
        assertThat(body.arguments().get(0)).isInstanceOf(ColonVar.class);
    }

    @Test
    void repeat_with_list_body_parses_inner_function_calls() {
        ParseResult r = parse("REPEAT 4 [ FD 100 RT 90 ]");
        assertThat(r.diagnostics()).isEmpty();
        Command repeat = (Command) r.program().items().get(0);
        assertThat(repeat.head().lexeme()).isEqualTo("REPEAT");
        assertThat(repeat.arguments()).hasSize(2);
        assertThat(repeat.arguments().get(0)).isInstanceOf(NumberLit.class);
        ListLit list = (ListLit) repeat.arguments().get(1);
        assertThat(list.elements()).hasSize(2);
        FunctionCall fd = (FunctionCall) list.elements().get(0);
        assertThat(fd.head().lexeme()).isEqualTo("FD");
        assertThat(fd.arguments()).hasSize(1);
        FunctionCall rt = (FunctionCall) list.elements().get(1);
        assertThat(rt.head().lexeme()).isEqualTo("RT");
        assertThat(rt.arguments()).hasSize(1);
    }

    @Test
    void arithmetic_expressions_respect_precedence() {
        // MAKE "x 1 + 2 * 3  →  MAKE "x (1 + (2 * 3))
        ParseResult r = parse("MAKE \"x 1 + 2 * 3");
        assertThat(r.diagnostics()).isEmpty();
        Command make = (Command) r.program().items().get(0);
        assertThat(make.arguments()).hasSize(2);
        assertThat(make.arguments().get(0)).isInstanceOf(QuoteWord.class);
        BinaryOp outer = (BinaryOp) make.arguments().get(1);
        assertThat(outer.operator().lexeme()).isEqualTo("+");
        assertThat(outer.left()).isInstanceOf(NumberLit.class);
        BinaryOp inner = (BinaryOp) outer.right();
        assertThat(inner.operator().lexeme()).isEqualTo("*");
    }

    @Test
    void comparison_expression_inside_if() {
        ParseResult r = parse("IF :x > 0 [ FD 100 ]");
        assertThat(r.diagnostics()).isEmpty();
        Command ifCmd = (Command) r.program().items().get(0);
        assertThat(ifCmd.arguments()).hasSize(2);
        BinaryOp cmp = (BinaryOp) ifCmd.arguments().get(0);
        assertThat(cmp.operator().lexeme()).isEqualTo(">");
        assertThat(cmp.left()).isInstanceOf(ColonVar.class);
        assertThat(cmp.right()).isInstanceOf(NumberLit.class);
        ListLit then = (ListLit) ifCmd.arguments().get(1);
        assertThat(then.elements()).hasSize(1);
    }

    @Test
    void forward_reference_to_user_procedure_resolves_arity() {
        // The call appears before the definition; the two-pass parser consumes
        // `square` with one argument (80) because pass 1 sees the TO header.
        String src = "square 80\nTO square :size\n  FD :size\nEND\n";
        ParseResult r = parse(src);
        assertThat(r.diagnostics()).isEmpty();
        Command call = (Command) r.program().items().get(0);
        assertThat(call.head().lexeme()).isEqualTo("square");
        assertThat(call.arguments()).hasSize(1);
        assertThat(call.arguments().get(0)).isInstanceOf(NumberLit.class);
        assertThat(r.program().items().get(1)).isInstanceOf(ProcedureDef.class);
    }

    @Test
    void unknown_procedure_consumes_remaining_line_as_args() {
        // No diagnostic from the parser (semantic layer reports unknowns).
        ParseResult r = parse("UNKNOWN 1 2 3");
        Command cmd = (Command) r.program().items().get(0);
        assertThat(cmd.head().lexeme()).isEqualTo("UNKNOWN");
        assertThat(cmd.arguments()).hasSize(3);
    }

    @Test
    void unary_minus_on_argument() {
        ParseResult r = parse("FD -50");
        assertThat(r.diagnostics()).isEmpty();
        Command cmd = (Command) r.program().items().get(0);
        UnaryOp unary = (UnaryOp) cmd.arguments().get(0);
        assertThat(unary.operator().lexeme()).isEqualTo("-");
        assertThat(unary.operand()).isInstanceOf(NumberLit.class);
    }

    @Test
    void paren_expression_overrides_precedence() {
        ParseResult r = parse("MAKE \"x (1 + 2) * 3");
        assertThat(r.diagnostics()).isEmpty();
        Command make = (Command) r.program().items().get(0);
        BinaryOp mul = (BinaryOp) make.arguments().get(1);
        assertThat(mul.operator().lexeme()).isEqualTo("*");
    }

    @Test
    void function_call_inside_expression_consumes_its_arity() {
        // RANDOM 100 is an expression returning a number; MAKE consumes it as one argument.
        ParseResult r = parse("MAKE \"x RANDOM 100");
        assertThat(r.diagnostics()).isEmpty();
        Command make = (Command) r.program().items().get(0);
        assertThat(make.arguments()).hasSize(2);
        FunctionCall call = (FunctionCall) make.arguments().get(1);
        assertThat(call.head().lexeme()).isEqualTo("RANDOM");
        assertThat(call.arguments()).hasSize(1);
    }

    @Test
    void comments_are_invisible_to_the_parser() {
        ParseResult r = parse("FD 100 ; forward a lot\nRT 90");
        assertThat(r.diagnostics()).isEmpty();
        assertThat(r.program().items()).hasSize(2);
    }

    @Test
    void missing_end_emits_diagnostic_and_keeps_parsing() {
        ParseResult r = parse("TO foo\n  FD 100\n");
        assertThat(r.program().items()).hasSize(1);
        assertThat(r.program().items().get(0)).isInstanceOf(ProcedureDef.class);
        assertThat(r.diagnostics())
                .anyMatch(d -> d.getMessage().contains("expected END"));
    }

    @Test
    void to_without_name_emits_diagnostic_and_recovers() {
        ParseResult r = parse("TO\nFD 100\nEND\nTO good :x\nFD :x\nEND\n");
        assertThat(r.diagnostics())
                .anyMatch(d -> d.getMessage().contains("expected procedure name"));
        // The recovery keeps a ProcedureDef for the malformed TO and another for good.
        assertThat(r.program().items()).hasSize(2);
        ProcedureDef good = (ProcedureDef) r.program().items().get(1);
        assertThat(good.nameToken().lexeme()).isEqualTo("good");
    }

    @Test
    void fixture_square_logo_parses_cleanly() throws IOException {
        ParseResult r = parseFixture("square.logo");
        assertThat(r.diagnostics()).isEmpty();
        assertThat(r.program().items()).hasSize(2); // TO square + call
        assertThat(r.program().items().get(0)).isInstanceOf(ProcedureDef.class);
        assertThat(r.program().items().get(1)).isInstanceOf(Command.class);
    }

    @Test
    void fixture_polygon_logo_parses_cleanly() throws IOException {
        ParseResult r = parseFixture("polygon.logo");
        assertThat(r.diagnostics()).isEmpty();
        ProcedureDef def = (ProcedureDef) r.program().items().get(0);
        assertThat(def.parameterTokens()).hasSize(2);
    }

    @Test
    void fixture_nested_logo_parses_cleanly() throws IOException {
        ParseResult r = parseFixture("nested.logo");
        assertThat(r.diagnostics()).isEmpty();
        assertThat(r.program().items()).hasSize(3);
    }

    @Test
    void fixture_forward_ref_logo_parses_cleanly() throws IOException {
        ParseResult r = parseFixture("forward_ref.logo");
        assertThat(r.diagnostics()).isEmpty();
        Command call = (Command) r.program().items().get(0);
        assertThat(call.arguments()).hasSize(1);
    }

    @Test
    void fixture_broken_logo_recovers_and_still_parses_following_definitions() throws IOException {
        ParseResult r = parseFixture("broken.logo");
        assertThat(r.diagnostics()).isNotEmpty();
        // The well-formed TO good :x / END should still appear.
        boolean foundGood = r.program().items().stream()
                .filter(ProcedureDef.class::isInstance)
                .map(ProcedureDef.class::cast)
                .anyMatch(def -> "good".equals(def.nameToken().lexeme()));
        assertThat(foundGood).isTrue();
    }

    @Test
    void too_many_arguments_emits_diagnostic() {
        ParseResult r = parse("FD 10 20\n");
        assertThat(r.diagnostics())
                .anyMatch(d -> d.getMessage().contains("too many arguments")
                        && d.getMessage().contains("FD"));
    }

    @Test
    void too_few_arguments_emits_diagnostic() {
        ParseResult r = parse("SETXY 1\n");
        assertThat(r.diagnostics())
                .anyMatch(d -> d.getMessage().contains("too few arguments")
                        && d.getMessage().contains("SETXY"));
    }

    @Test
    void juxtaposed_commands_on_same_line_are_not_too_many_args() {
        // FD 10 RT 90 is two statements on one line, not "too many for FD".
        ParseResult r = parse("FD 10 RT 90\n");
        assertThat(r.diagnostics())
                .noneMatch(d -> d.getMessage().contains("too many arguments"));
        assertThat(r.program().items()).hasSize(2);
    }

    @Test
    void crlf_line_endings_parse_cleanly() {
        ParseResult r = parse("TO greet\r\n  FD 10\r\nEND\r\n");
        assertThat(r.diagnostics()).isEmpty();
        assertThat(r.program().items()).hasSize(1);
    }

    @Test
    void mixed_case_keywords_and_builtin_aliases_are_recognised() {
        // to/end keyword recognition + Fd alias should both work case-insensitively.
        ParseResult r = parse("to greet\n  Fd 10\nend\n");
        assertThat(r.diagnostics()).isEmpty();
        assertThat(r.program().items()).hasSize(1);
        ProcedureDef def = (ProcedureDef) r.program().items().get(0);
        assertThat(def.nameToken().lexeme()).isEqualTo("greet");
    }

    // --- helpers ----------------------------------------------------------------------

    private static ParseResult parse(String source) {
        List<Token> tokens = new LogoLexer(source).tokenize();
        return new LogoParser(tokens, BUILTINS).parse();
    }

    private static ParseResult parseFixture(String filename) throws IOException {
        Path path = Path.of("src/test/resources/fixtures", filename);
        String source = Files.readString(path, StandardCharsets.UTF_8);
        return parse(source);
    }

    @SuppressWarnings("unused")
    private static void debugItems(List<TopLevel> items) {
        for (TopLevel item : items) {
            Objects.requireNonNull(item); // silence lint
        }
    }
}
