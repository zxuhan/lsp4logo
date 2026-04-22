package com.example.logolsp.features;

import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.document.ParsedDocument;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HoverProviderTest {

    private static final LogoBuiltins BUILTINS = LogoBuiltins.loadDefault();
    private static final String URI = "file:///t.logo";

    @Test
    void builtin_call_hover_shows_signature_and_doc() {
        Hover h = hover("FD 100\n", position("FD 100\n", "FD", 1));
        assertThat(h).isNotNull();
        assertThat(h.getContents().getRight().getKind()).isEqualTo(MarkupKind.MARKDOWN);
        String md = h.getContents().getRight().getValue();
        assertThat(md)
                .contains("FORWARD")
                .contains("arity 1")
                .contains("forward")
                .contains("Turtle Academy built-in");
    }

    @Test
    void builtin_alias_shows_canonical_form() {
        Hover h = hover("FD 100\n", position("FD 100\n", "FD", 1));
        assertThat(h.getContents().getRight().getValue())
                .contains("FORWARD")
                .contains("aliases: FD");
    }

    @Test
    void user_procedure_hover_shows_TO_signature() {
        String src = "TO square :size\n  FD :size\nEND\nsquare 10\n";
        Hover h = hover(src, position(src, "square", 2)); // call site
        assertThat(h).isNotNull();
        assertThat(h.getContents().getRight().getValue())
                .contains("TO square :size")
                .contains("User-defined procedure");
    }

    @Test
    void hover_on_TO_name_in_header_shows_signature_too() {
        String src = "TO square :size\n  FD :size\nEND\n";
        Hover h = hover(src, position(src, "square", 1));
        assertThat(h).isNotNull();
        assertThat(h.getContents().getRight().getValue()).contains("TO square :size");
    }

    @Test
    void parameter_reference_hover_shows_parameter_kind() {
        String src = "TO square :size\n  FD :size\nEND\n";
        Hover h = hover(src, position(src, ":size", 2));
        assertThat(h).isNotNull();
        assertThat(h.getContents().getRight().getValue())
                .contains(":size")
                .contains("parameter");
    }

    @Test
    void local_hover_shows_local_kind() {
        String src = "TO foo\n  LOCAL \"counter\n  MAKE \"counter :counter + 1\nEND\n";
        Hover h = hover(src, position(src, ":counter", 1));
        assertThat(h).isNotNull();
        assertThat(h.getContents().getRight().getValue())
                .contains(":counter")
                .contains("local");
    }

    @Test
    void global_hover_shows_global_kind() {
        String src = "MAKE \"total 0\nTO foo\n  FD :total\nEND\n";
        Hover h = hover(src, position(src, ":total", 1));
        assertThat(h).isNotNull();
        assertThat(h.getContents().getRight().getValue())
                .contains(":total")
                .contains("global");
    }

    @Test
    void undefined_variable_hover_marks_it_undefined() {
        String src = "TO foo\n  FD :mystery\nEND\n";
        Hover h = hover(src, position(src, ":mystery", 1));
        assertThat(h).isNotNull();
        assertThat(h.getContents().getRight().getValue()).contains("Undefined");
    }

    @Test
    void number_does_not_produce_hover() {
        String src = "FD 100\n";
        Hover h = hover(src, position(src, "100", 1));
        assertThat(h).isNull();
    }

    @Test
    void whitespace_does_not_produce_hover() {
        String src = "FD 100\n";
        Hover h = hover(src, new Position(0, 6));
        assertThat(h).isNull();
    }

    @Test
    void hover_range_matches_the_token_range() {
        String src = "FD 100\n";
        Hover h = hover(src, position(src, "FD", 1));
        assertThat(h.getRange().getStart().getLine()).isEqualTo(0);
        assertThat(h.getRange().getStart().getCharacter()).isEqualTo(0);
        assertThat(h.getRange().getEnd().getCharacter()).isEqualTo(2);
    }

    // --- helpers -----------------------------------------------------------------

    private static Hover hover(String src, Position pos) {
        ParsedDocument doc = ParsedDocument.parse(URI, src, BUILTINS);
        return HoverProvider.hover(doc, BUILTINS, pos);
    }

    private static Position position(String source, String needle, int occurrence) {
        int idx = -1;
        for (int i = 0; i < occurrence; i++) {
            idx = source.indexOf(needle, idx + 1);
            if (idx < 0) throw new IllegalArgumentException("no occurrence " + occurrence);
        }
        int pick = idx + Math.min(1, needle.length() - 1);
        int line = 0, col = 0;
        for (int i = 0; i < pick; i++) {
            if (source.charAt(i) == '\n') { line++; col = 0; } else col++;
        }
        return new Position(line, col);
    }
}
