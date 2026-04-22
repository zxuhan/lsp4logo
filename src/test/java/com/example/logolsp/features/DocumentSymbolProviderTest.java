package com.example.logolsp.features;

import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.document.ParsedDocument;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSymbolProviderTest {

    private static final LogoBuiltins BUILTINS = LogoBuiltins.loadDefault();

    @Test
    void empty_program_produces_no_symbols() {
        assertThat(symbols("")).isEmpty();
    }

    @Test
    void top_level_commands_are_not_surfaced_as_symbols() {
        assertThat(symbols("FD 100\n")).isEmpty();
    }

    @Test
    void single_TO_produces_one_Function_symbol() {
        List<DocumentSymbol> syms = symbols("TO greet\n  FD 10\nEND\n");
        assertThat(syms).hasSize(1);
        DocumentSymbol s = syms.get(0);
        assertThat(s.getName()).isEqualTo("greet");
        assertThat(s.getKind()).isEqualTo(SymbolKind.Function);
        assertThat(s.getSelectionRange().getStart().getLine()).isEqualTo(0);
    }

    @Test
    void procedure_with_parameters_has_them_as_children_of_kind_Variable() {
        List<DocumentSymbol> syms = symbols("TO square :size :color\n  FD :size\nEND\n");
        assertThat(syms).hasSize(1);
        DocumentSymbol s = syms.get(0);
        assertThat(s.getDetail()).isEqualTo(":size :color");
        assertThat(s.getChildren()).hasSize(2);
        assertThat(s.getChildren().get(0).getKind()).isEqualTo(SymbolKind.Variable);
        assertThat(s.getChildren().get(0).getName()).isEqualTo(":size");
        assertThat(s.getChildren().get(1).getName()).isEqualTo(":color");
    }

    @Test
    void procedure_without_parameters_has_no_children_and_no_detail() {
        List<DocumentSymbol> syms = symbols("TO blank\n  FD 10\nEND\n");
        DocumentSymbol s = syms.get(0);
        assertThat(s.getDetail()).isNull();
        assertThat(s.getChildren()).isNullOrEmpty();
    }

    @Test
    void multiple_TO_definitions_appear_in_source_order() {
        String src = "TO a\nEND\nTO b :x\n  FD :x\nEND\n";
        List<DocumentSymbol> syms = symbols(src);
        assertThat(syms).hasSize(2);
        assertThat(syms.get(0).getName()).isEqualTo("a");
        assertThat(syms.get(1).getName()).isEqualTo("b");
    }

    @Test
    void anonymous_procedure_from_parser_recovery_is_skipped() {
        // `TO\nEND` — parser recovers with an empty name; nothing useful to outline.
        List<DocumentSymbol> syms = symbols("TO\nEND\n");
        assertThat(syms).isEmpty();
    }

    @Test
    void range_spans_the_entire_TO_END_block_and_selectionRange_is_the_name() {
        String src = "TO greet\n  FD 10\nEND\n";
        DocumentSymbol s = symbols(src).get(0);
        // range starts at line 0 (TO) and ends at line 2 (END)
        assertThat(s.getRange().getStart().getLine()).isEqualTo(0);
        assertThat(s.getRange().getEnd().getLine()).isEqualTo(2);
        // selectionRange is just the "greet" token
        assertThat(s.getSelectionRange().getStart().getLine()).isEqualTo(0);
        assertThat(s.getSelectionRange().getEnd().getLine()).isEqualTo(0);
        // And it spans exactly the 5 characters of "greet"
        int span = s.getSelectionRange().getEnd().getCharacter()
                - s.getSelectionRange().getStart().getCharacter();
        assertThat(span).isEqualTo("greet".length());
    }

    private static List<DocumentSymbol> symbols(String src) {
        ParsedDocument doc = ParsedDocument.parse("file:///t.logo", src, BUILTINS);
        return DocumentSymbolProvider.documentSymbols(doc);
    }
}
