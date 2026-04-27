package com.example.logolsp.analysis;

import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.lexer.LogoLexer;
import com.example.logolsp.parser.LogoParser;
import com.example.logolsp.parser.ParseResult;
import com.example.logolsp.parser.ast.Ast.ProcedureDef;
import com.example.logolsp.parser.ast.Ast.TopLevel;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzerTest {

    private static final LogoBuiltins BUILTINS = LogoBuiltins.loadDefault();

    @Test
    void empty_program_produces_empty_global_and_no_diagnostics() {
        SymbolTable st = analyze("");
        assertThat(st.global().localSymbols()).isEmpty();
        assertThat(st.diagnostics()).isEmpty();
    }

    @Test
    void TO_definition_registers_procedure_in_global_scope() {
        SymbolTable st = analyze("TO foo\n  FD 100\nEND\n");
        Optional<Symbol> foo = st.global().lookupLocal("foo");
        assertThat(foo).isPresent();
        assertThat(foo.get().kind()).isEqualTo(Symbol.Kind.PROCEDURE);
    }

    @Test
    void parameters_live_in_the_procedure_scope_not_global() {
        ParseResult r = parse("TO square :size\n  FD :size\nEND\n");
        SymbolTable st = Analyzer.analyze(r.program(), BUILTINS);
        ProcedureDef def = (ProcedureDef) r.program().items().get(0);
        Scope procScope = st.scopeOf(def);
        assertThat(procScope.lookupLocal("size")).isPresent();
        assertThat(st.global().lookupLocal("size")).isEmpty();
        Symbol param = procScope.lookupLocal("size").get();
        assertThat(param.kind()).isEqualTo(Symbol.Kind.PARAMETER);
    }

    @Test
    void LOCAL_inside_a_procedure_declares_into_the_procedure_scope() {
        ParseResult r = parse("TO foo\n  LOCAL \"counter\n  MAKE \"counter 0\nEND\n");
        SymbolTable st = Analyzer.analyze(r.program(), BUILTINS);
        ProcedureDef def = (ProcedureDef) r.program().items().get(0);
        Scope procScope = st.scopeOf(def);
        Optional<Symbol> counter = procScope.lookupLocal("counter");
        assertThat(counter).isPresent();
        assertThat(counter.get().kind()).isEqualTo(Symbol.Kind.LOCAL);
        // And the MAKE did NOT create a global because LOCAL shadows.
        assertThat(st.global().lookupLocal("counter")).isEmpty();
    }

    @Test
    void MAKE_at_top_level_declares_a_global() {
        SymbolTable st = analyze("MAKE \"total 0\n");
        Optional<Symbol> total = st.global().lookupLocal("total");
        assertThat(total).isPresent();
        assertThat(total.get().kind()).isEqualTo(Symbol.Kind.GLOBAL);
    }

    @Test
    void MAKE_inside_procedure_without_visible_binding_creates_a_global() {
        ParseResult r = parse("TO bump\n  MAKE \"hits 1\nEND\n");
        SymbolTable st = Analyzer.analyze(r.program(), BUILTINS);
        ProcedureDef def = (ProcedureDef) r.program().items().get(0);
        // Not in the procedure scope…
        assertThat(st.scopeOf(def).lookupLocal("hits")).isEmpty();
        // …but a new global.
        Symbol hits = st.global().lookupLocal("hits").orElseThrow();
        assertThat(hits.kind()).isEqualTo(Symbol.Kind.GLOBAL);
    }

    @Test
    void reference_to_parameter_resolves_without_undefined_variable_diagnostic() {
        SymbolTable st = analyze("TO square :size\n  FD :size\nEND\n");
        assertThat(st.diagnostics())
                .noneMatch(d -> d.getMessage().contains("undefined variable"));
    }

    @Test
    void undefined_variable_emits_diagnostic() {
        SymbolTable st = analyze("TO oops\n  FD :missing\nEND\n");
        assertThat(st.diagnostics())
                .anyMatch(d -> d.getMessage().equals("undefined variable: :missing"));
    }

    @Test
    void unknown_procedure_call_emits_diagnostic() {
        SymbolTable st = analyze("HAMBURGER 10\n");
        assertThat(st.diagnostics())
                .anyMatch(d -> d.getMessage().equals("unknown procedure: HAMBURGER"));
    }

    @Test
    void known_builtin_call_does_not_emit_unknown_procedure() {
        SymbolTable st = analyze("FD 100\n");
        assertThat(st.diagnostics()).isEmpty();
    }

    @Test
    void forward_reference_to_user_proc_is_not_flagged() {
        SymbolTable st = analyze("square 80\nTO square :size\n  FD :size\nEND\n");
        assertThat(st.diagnostics()).isEmpty();
    }

    @Test
    void duplicate_TO_definition_emits_diagnostic() {
        SymbolTable st = analyze("TO foo\nEND\nTO foo\nEND\n");
        assertThat(st.diagnostics())
                .anyMatch(d -> d.getMessage().equals("duplicate procedure definition: foo"));
    }

    @Test
    void duplicate_parameter_emits_diagnostic() {
        SymbolTable st = analyze("TO foo :x :x\n  FD :x\nEND\n");
        assertThat(st.diagnostics())
                .anyMatch(d -> d.getMessage().equals("duplicate parameter: x"));
    }

    @Test
    void case_insensitive_reference_resolves_across_case_difference() {
        SymbolTable st = analyze("TO Foo\nEND\nfoo\n");
        assertThat(st.diagnostics()).isEmpty();
    }

    @Test
    void fixture_square_analyses_cleanly() throws IOException {
        SymbolTable st = analyzeFixture("square.logo");
        assertThat(st.diagnostics()).isEmpty();
    }

    @Test
    void fixture_polygon_analyses_cleanly() throws IOException {
        SymbolTable st = analyzeFixture("polygon.logo");
        assertThat(st.diagnostics()).isEmpty();
    }

    @Test
    void fixture_nested_analyses_cleanly() throws IOException {
        SymbolTable st = analyzeFixture("nested.logo");
        assertThat(st.diagnostics()).isEmpty();
    }

    @Test
    void fixture_forward_ref_analyses_cleanly() throws IOException {
        SymbolTable st = analyzeFixture("forward_ref.logo");
        assertThat(st.diagnostics()).isEmpty();
    }

    @Test
    void unused_parameter_emits_warning_not_error() {
        SymbolTable st = analyze("TO ignore :unused\n  FD 100\nEND\n");
        Diagnostic warning = st.diagnostics().stream()
                .filter(d -> d.getMessage().equals("unused parameter: :unused"))
                .findFirst()
                .orElseThrow();
        assertThat(warning.getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
    }

    @Test
    void unused_local_emits_warning() {
        SymbolTable st = analyze("TO foo\n  LOCAL \"stale\n  FD 100\nEND\n");
        Diagnostic warning = st.diagnostics().stream()
                .filter(d -> d.getMessage().equals("unused local: :stale"))
                .findFirst()
                .orElseThrow();
        assertThat(warning.getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
    }

    @Test
    void referenced_parameter_does_not_trigger_unused_warning() {
        SymbolTable st = analyze("TO square :size\n  FD :size\nEND\n");
        assertThat(st.diagnostics()).noneMatch(d -> d.getMessage().startsWith("unused parameter"));
    }

    @Test
    void MAKE_writing_to_a_local_does_not_count_as_use() {
        // Writing to a LOCAL isn't a read — the local should still be flagged.
        SymbolTable st = analyze("TO foo\n  LOCAL \"x\n  MAKE \"x 5\nEND\n");
        assertThat(st.diagnostics())
                .anyMatch(d -> d.getMessage().equals("unused local: :x"));
    }

    @Test
    void local_used_on_right_hand_side_of_MAKE_is_not_flagged_unused() {
        SymbolTable st = analyze("TO foo\n  LOCAL \"x\n  MAKE \"x 1\n  MAKE \"y :x + 1\nEND\n");
        assertThat(st.diagnostics())
                .noneMatch(d -> d.getMessage().equals("unused local: :x"));
    }

    @Test
    void top_level_MAKE_global_is_not_flagged_unused() {
        // Globals may be used externally; we only warn on PARAMETER and LOCAL.
        SymbolTable st = analyze("MAKE \"total 0\n");
        assertThat(st.diagnostics())
                .noneMatch(d -> d.getMessage().startsWith("unused"));
    }

    @Test
    void reference_to_local_inside_repeat_body_resolves_to_its_declaration() {
        ParseResult r = parse("TO loopy\n  LOCAL \"i\n  MAKE \"i 0\n  REPEAT 3 [ MAKE \"i :i + 1 ]\nEND\n");
        SymbolTable st = Analyzer.analyze(r.program(), BUILTINS);
        // No undefined-variable diagnostic for :i inside the REPEAT body.
        assertThat(st.diagnostics())
                .noneMatch(d -> d.getMessage().contains("undefined variable"));
        ProcedureDef def = (ProcedureDef) r.program().items().get(0);
        assertThat(st.scopeOf(def).lookupLocal("i")).isPresent();
    }

    // --- helpers ------------------------------------------------------------------

    private static ParseResult parse(String source) {
        return new LogoParser(new LogoLexer(source).tokenize(), BUILTINS).parse();
    }

    private static SymbolTable analyze(String source) {
        return Analyzer.analyze(parse(source).program(), BUILTINS);
    }

    private static SymbolTable analyzeFixture(String filename) throws IOException {
        Path path = Path.of("src/test/resources/fixtures", filename);
        String src = Files.readString(path, StandardCharsets.UTF_8);
        return analyze(src);
    }

    @SuppressWarnings("unused")
    private static Diagnostic firstDiag(SymbolTable st) {
        return st.diagnostics().get(0);
    }

    @SuppressWarnings("unused")
    private static TopLevel first(ParseResult r) {
        return r.program().items().get(0);
    }
}
