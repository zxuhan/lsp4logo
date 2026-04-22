package com.example.logolsp.document;

import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.parser.ast.Ast.Command;
import com.example.logolsp.parser.ast.Ast.ProcedureDef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParsedDocumentTest {

    private static final LogoBuiltins BUILTINS = LogoBuiltins.loadDefault();

    @Test
    void parse_populates_tokens_program_and_symbol_table() {
        ParsedDocument doc = ParsedDocument.parse("file:///t.logo", "FD 100\n", BUILTINS);
        assertThat(doc.tokens()).isNotEmpty();
        assertThat(doc.program().items()).hasSize(1);
        assertThat(doc.program().items().get(0)).isInstanceOf(Command.class);
        assertThat(doc.symbolTable().global().localSymbols()).isEmpty();
    }

    @Test
    void parse_combines_parser_and_analyzer_diagnostics() {
        ParsedDocument doc = ParsedDocument.parse("file:///t.logo",
                "TO foo\n  FD :missing\nEND\n", BUILTINS);
        // Analyzer-level: undefined variable
        assertThat(doc.diagnostics())
                .anyMatch(d -> d.getMessage().equals("undefined variable: :missing"));
    }

    @Test
    void user_defined_procedure_lands_in_global_scope() {
        ParsedDocument doc = ParsedDocument.parse("file:///t.logo",
                "TO square :size\n  FD :size\nEND\n", BUILTINS);
        assertThat(doc.symbolTable().global().lookupLocal("square")).isPresent();
        ProcedureDef def = (ProcedureDef) doc.program().items().get(0);
        assertThat(doc.symbolTable().scopeOf(def).lookupLocal("size")).isPresent();
    }

    @Test
    void tokens_and_diagnostics_are_defensively_copied() {
        ParsedDocument doc = ParsedDocument.parse("file:///t.logo", "FD 100", BUILTINS);
        assertThat(doc.tokens()).isUnmodifiable();
        assertThat(doc.diagnostics()).isUnmodifiable();
    }
}
