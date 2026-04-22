package com.example.logolsp.features;

import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.document.ParsedDocument;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionProviderTest {

    private static final LogoBuiltins BUILTINS = LogoBuiltins.loadDefault();
    private static final String URI = "file:///t.logo";

    @Test
    void empty_document_returns_keywords_and_builtins() {
        List<CompletionItem> items = completions("", new Position(0, 0));
        assertLabels(items).contains("TO", "END", "REPEAT", "FORWARD", "FD", "RANDOM");
    }

    @Test
    void after_colon_returns_in_scope_variables_and_no_keywords() {
        String src = "TO square :size\n  FD :\nEND\n";
        // Cursor right after ':' on line 1, column 6 (after "  FD :")
        List<CompletionItem> items = completions(src, new Position(1, 6));
        assertLabels(items).contains("size");
        assertLabels(items).doesNotContain("TO", "FORWARD");
    }

    @Test
    void after_colon_inside_procedure_sees_parameters_and_globals_but_not_other_procs_params() {
        String src = """
                MAKE "counter 0
                TO alpha :a
                  FD :a
                END
                TO beta :b
                  FD :
                END
                """;
        // Inside beta, column 6 after "  FD :"
        List<CompletionItem> items = completions(src, new Position(5, 6));
        assertLabels(items)
                .contains("b", "counter")
                .doesNotContain("a");
    }

    @Test
    void user_defined_procedures_show_up_in_completions() {
        String src = "TO greet\n  FD 10\nEND\n";
        List<CompletionItem> items = completions(src, new Position(3, 0));
        assertLabels(items).contains("greet");
    }

    @Test
    void builtins_include_both_canonical_name_and_alias() {
        List<CompletionItem> items = completions("", new Position(0, 0));
        assertLabels(items).contains("FORWARD", "FD", "BACK", "BK");
    }

    @Test
    void builtin_completion_carries_documentation() {
        List<CompletionItem> items = completions("", new Position(0, 0));
        CompletionItem fd = items.stream()
                .filter(i -> "FD".equals(i.getLabel()))
                .findFirst()
                .orElseThrow();
        assertThat(fd.getKind()).isEqualTo(CompletionItemKind.Function);
        assertThat(fd.getDocumentation().getLeft()).contains("forward");
    }

    @Test
    void keyword_completion_has_Keyword_kind() {
        List<CompletionItem> items = completions("", new Position(0, 0));
        CompletionItem to = items.stream()
                .filter(i -> "TO".equals(i.getLabel()))
                .findFirst()
                .orElseThrow();
        assertThat(to.getKind()).isEqualTo(CompletionItemKind.Keyword);
    }

    @Test
    void variable_completion_has_Variable_kind_and_no_sigil_in_label() {
        String src = "TO foo :x\n  FD :\nEND\n";
        List<CompletionItem> items = completions(src, new Position(1, 6));
        CompletionItem x = items.stream()
                .filter(i -> "x".equals(i.getLabel()))
                .findFirst()
                .orElseThrow();
        assertThat(x.getKind()).isEqualTo(CompletionItemKind.Variable);
    }

    @Test
    void cursor_outside_any_procedure_sees_only_globals_for_variable_context() {
        String src = "MAKE \"total 0\n:";
        List<CompletionItem> items = completions(src, new Position(1, 1));
        assertLabels(items).contains("total");
    }

    @Test
    void scope_chain_is_walked_once_per_name() {
        // If a name exists in both global and procedure scope (shadowing), we emit it once.
        String src = """
                MAKE "shared 0
                TO foo
                  LOCAL "shared
                  MAKE "shared :
                END
                """;
        List<CompletionItem> items = completions(src, new Position(3, 16));
        long sharedCount = items.stream()
                .filter(i -> "shared".equals(i.getLabel()))
                .count();
        assertThat(sharedCount).isEqualTo(1);
    }

    // --- helpers -----------------------------------------------------------------

    private static List<CompletionItem> completions(String src, Position pos) {
        ParsedDocument doc = ParsedDocument.parse(URI, src, BUILTINS);
        return CompletionProvider.completion(doc, BUILTINS, pos);
    }

    private static org.assertj.core.api.ListAssert<String> assertLabels(List<CompletionItem> items) {
        return assertThat(items.stream().map(CompletionItem::getLabel).toList());
    }
}
