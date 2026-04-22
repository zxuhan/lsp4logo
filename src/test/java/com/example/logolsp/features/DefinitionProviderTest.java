package com.example.logolsp.features;

import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.document.ParsedDocument;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefinitionProviderTest {

    private static final LogoBuiltins BUILTINS = LogoBuiltins.loadDefault();
    private static final String URI = "file:///t.logo";

    @Test
    void variable_reference_resolves_to_parameter() {
        String src = "TO square :size\n  FD :size\nEND\n";
        Position usage = positionOf(src, ":size", 2); // the 2nd occurrence, inside FD
        List<Location> locs = resolve(src, usage);
        assertThat(locs).singleElement()
                .satisfies(l -> assertThat(l.getRange()).isEqualTo(rangeOf(src, ":size", 1)));
    }

    @Test
    void variable_reference_resolves_to_LOCAL_declaration() {
        String src = "TO foo\n  LOCAL \"counter\n  MAKE \"counter :counter + 1\nEND\n";
        Position usage = positionOf(src, ":counter", 1);
        List<Location> locs = resolve(src, usage);
        assertThat(locs).singleElement()
                .satisfies(l -> assertThat(l.getRange()).isEqualTo(rangeOf(src, "\"counter", 1)));
    }

    @Test
    void variable_reference_resolves_to_MAKE_global() {
        String src = "MAKE \"x 5\nTO foo\n  FD :x\nEND\n";
        Position usage = positionOf(src, ":x", 1);
        List<Location> locs = resolve(src, usage);
        assertThat(locs).singleElement()
                .satisfies(l -> assertThat(l.getRange()).isEqualTo(rangeOf(src, "\"x", 1)));
    }

    @Test
    void procedure_call_resolves_to_TO_definition() {
        String src = "TO greet\n  FD 10\nEND\ngreet\n";
        Position usage = positionOf(src, "greet", 2); // the call site
        List<Location> locs = resolve(src, usage);
        assertThat(locs).singleElement()
                .satisfies(l -> assertThat(l.getRange()).isEqualTo(rangeOf(src, "greet", 1)));
    }

    @Test
    void forward_reference_resolves_to_later_TO() {
        String src = "greet\nTO greet\n  FD 10\nEND\n";
        Position usage = positionOf(src, "greet", 1); // first occurrence is the call
        List<Location> locs = resolve(src, usage);
        assertThat(locs).singleElement()
                .satisfies(l -> assertThat(l.getRange()).isEqualTo(rangeOf(src, "greet", 2)));
    }

    @Test
    void builtin_call_returns_empty_because_no_source_location() {
        String src = "FD 100\n";
        Position usage = positionOf(src, "FD", 1);
        assertThat(resolve(src, usage)).isEmpty();
    }

    @Test
    void unknown_procedure_call_returns_empty() {
        String src = "HAMBURGER 1\n";
        Position usage = positionOf(src, "HAMBURGER", 1);
        assertThat(resolve(src, usage)).isEmpty();
    }

    @Test
    void click_on_procedure_name_in_TO_returns_self_location() {
        String src = "TO foo\n  FD 10\nEND\n";
        Position onFoo = positionOf(src, "foo", 1);
        List<Location> locs = resolve(src, onFoo);
        assertThat(locs).singleElement()
                .satisfies(l -> assertThat(l.getRange()).isEqualTo(rangeOf(src, "foo", 1)));
    }

    @Test
    void click_on_parameter_in_TO_header_returns_self_location() {
        String src = "TO square :size\n  FD :size\nEND\n";
        Position onDecl = positionOf(src, ":size", 1);
        List<Location> locs = resolve(src, onDecl);
        assertThat(locs).singleElement()
                .satisfies(l -> assertThat(l.getRange()).isEqualTo(rangeOf(src, ":size", 1)));
    }

    @Test
    void click_on_whitespace_returns_empty() {
        String src = "FD 100\n";
        Position onSpace = new Position(0, 7); // past the end of the line
        assertThat(resolve(src, onSpace)).isEmpty();
    }

    @Test
    void reference_inside_REPEAT_body_resolves() {
        String src = "TO square :size\n  REPEAT 4 [ FD :size RT 90 ]\nEND\n";
        Position usage = positionOf(src, ":size", 2); // the one inside REPEAT
        List<Location> locs = resolve(src, usage);
        assertThat(locs).singleElement()
                .satisfies(l -> assertThat(l.getRange()).isEqualTo(rangeOf(src, ":size", 1)));
    }

    @Test
    void undefined_variable_returns_empty() {
        String src = "TO foo\n  FD :mystery\nEND\n";
        Position usage = positionOf(src, ":mystery", 1);
        assertThat(resolve(src, usage)).isEmpty();
    }

    // --- helpers -----------------------------------------------------------------

    private static List<Location> resolve(String source, Position pos) {
        ParsedDocument doc = ParsedDocument.parse(URI, source, BUILTINS);
        return DefinitionProvider.definition(doc, pos);
    }

    /** Returns the position at the start of the {@code occurrence}-th (1-based) match. */
    private static Position positionOf(String source, String needle, int occurrence) {
        int idx = -1;
        for (int i = 0; i < occurrence; i++) {
            idx = source.indexOf(needle, idx + 1);
            if (idx < 0) throw new IllegalArgumentException(
                    "occurrence " + occurrence + " of '" + needle + "' not found");
        }
        // Pick a position inside the match (one char after start, if possible).
        int pickIdx = idx + Math.min(1, needle.length() - 1);
        return indexToPosition(source, pickIdx);
    }

    /** The LSP range spanning the {@code occurrence}-th occurrence of {@code needle}. */
    private static Range rangeOf(String source, String needle, int occurrence) {
        int idx = -1;
        for (int i = 0; i < occurrence; i++) {
            idx = source.indexOf(needle, idx + 1);
            if (idx < 0) throw new IllegalArgumentException(
                    "occurrence " + occurrence + " of '" + needle + "' not found");
        }
        return new Range(indexToPosition(source, idx),
                indexToPosition(source, idx + needle.length()));
    }

    private static Position indexToPosition(String source, int index) {
        int line = 0, col = 0;
        for (int i = 0; i < index; i++) {
            if (source.charAt(i) == '\n') { line++; col = 0; } else col++;
        }
        return new Position(line, col);
    }
}
