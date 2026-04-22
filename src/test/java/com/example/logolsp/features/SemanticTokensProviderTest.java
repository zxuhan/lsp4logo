package com.example.logolsp.features;

import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.document.ParsedDocument;
import org.eclipse.lsp4j.SemanticTokens;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticTokensProviderTest {

    private static final LogoBuiltins BUILTINS = LogoBuiltins.loadDefault();

    private static final int T_KEYWORD   = index("keyword");
    private static final int T_FUNCTION  = index("function");
    private static final int T_PARAMETER = index("parameter");
    private static final int T_VARIABLE  = index("variable");
    private static final int T_STRING    = index("string");
    private static final int T_NUMBER    = index("number");
    private static final int T_COMMENT   = index("comment");
    private static final int T_OPERATOR  = index("operator");

    private static final int MOD_DECLARATION     = 1;
    private static final int MOD_DEFAULT_LIBRARY = 2;

    @Test
    void empty_source_produces_empty_data() {
        SemanticTokens tokens = compute("");
        assertThat(tokens.getData()).isEmpty();
    }

    @Test
    void single_builtin_call_emits_function_with_defaultLibrary_and_number() {
        List<DecodedToken> decoded = decode(compute("FD 100\n"));
        assertThat(decoded).hasSize(2);
        assertThat(decoded.get(0)).isEqualTo(new DecodedToken(0, 0, 2, T_FUNCTION, MOD_DEFAULT_LIBRARY));
        assertThat(decoded.get(1)).isEqualTo(new DecodedToken(0, 3, 3, T_NUMBER, 0));
    }

    @Test
    void TO_block_classifies_keyword_function_decl_parameter_and_body() {
        String src = "TO square :size\n  FD :size\nEND\n";
        List<DecodedToken> decoded = decode(compute(src));
        // TO (keyword), square (function declaration), :size (parameter decl),
        // FD (function defaultLibrary), :size (parameter ref), END (keyword)
        assertThat(decoded).hasSize(6);
        assertThat(decoded.get(0).typeIdx()).isEqualTo(T_KEYWORD);     // TO
        assertThat(decoded.get(1)).matches(d -> d.typeIdx() == T_FUNCTION
                && (d.modifiers() & MOD_DECLARATION) != 0);            // square
        assertThat(decoded.get(2).typeIdx()).isEqualTo(T_PARAMETER);   // :size decl
        assertThat(decoded.get(3)).matches(d -> d.typeIdx() == T_FUNCTION
                && (d.modifiers() & MOD_DEFAULT_LIBRARY) != 0);        // FD
        assertThat(decoded.get(4).typeIdx()).isEqualTo(T_PARAMETER);   // :size ref
        assertThat(decoded.get(5).typeIdx()).isEqualTo(T_KEYWORD);     // END
    }

    @Test
    void user_procedure_call_has_no_defaultLibrary_modifier() {
        String src = "TO greet\nEND\ngreet\n";
        List<DecodedToken> decoded = decode(compute(src));
        // greet at the call site (last token) is FUNCTION with no modifiers.
        DecodedToken callSite = decoded.get(decoded.size() - 1);
        assertThat(callSite.typeIdx()).isEqualTo(T_FUNCTION);
        assertThat(callSite.modifiers()).isEqualTo(0);
    }

    @Test
    void variable_reference_to_global_uses_variable_token_type() {
        String src = "MAKE \"total 0\nTO foo\n  FD :total\nEND\n";
        List<DecodedToken> decoded = decode(compute(src));
        // Find the :total reference (line 2, after FD)
        DecodedToken ref = decoded.stream()
                .filter(d -> d.typeIdx() == T_VARIABLE)
                .findFirst()
                .orElseThrow();
        assertThat(ref.line()).isEqualTo(2);
    }

    @Test
    void quoted_word_is_tagged_as_string() {
        List<DecodedToken> decoded = decode(compute("MAKE \"name 5\n"));
        assertThat(decoded).anyMatch(d -> d.typeIdx() == T_STRING);
    }

    @Test
    void comment_is_tagged_as_comment() {
        List<DecodedToken> decoded = decode(compute("; hello\nFD 1\n"));
        assertThat(decoded.get(0).typeIdx()).isEqualTo(T_COMMENT);
    }

    @Test
    void operators_are_tagged_as_operator() {
        List<DecodedToken> decoded = decode(compute("MAKE \"x 1 + 2 * 3\n"));
        long operatorCount = decoded.stream().filter(d -> d.typeIdx() == T_OPERATOR).count();
        assertThat(operatorCount).isEqualTo(2); // + and *
    }

    @Test
    void REPEAT_IF_IFELSE_MAKE_LOCAL_OUTPUT_STOP_are_keywords() {
        for (String kw : List.of("REPEAT", "IF", "IFELSE", "MAKE", "LOCAL", "OUTPUT", "STOP")) {
            // Use a minimal context so arity doesn't blow up: wrap in a lookup-friendly line.
            String src = kw + " 1 [ ]";
            List<DecodedToken> decoded = decode(compute(src));
            assertThat(decoded).as("%s should be a keyword", kw)
                    .anyMatch(d -> d.typeIdx() == T_KEYWORD && d.length() == kw.length());
        }
    }

    @Test
    void tokens_are_sorted_by_position_even_if_emitted_out_of_order() {
        // Operators inside a binary expression might be emitted between the operands —
        // the sort should still produce a left-to-right sequence.
        List<DecodedToken> decoded = decode(compute("MAKE \"x 1 + 2\n"));
        for (int i = 1; i < decoded.size(); i++) {
            DecodedToken prev = decoded.get(i - 1);
            DecodedToken cur = decoded.get(i);
            assertThat(cur.line()).isGreaterThanOrEqualTo(prev.line());
            if (cur.line() == prev.line()) {
                assertThat(cur.startChar()).isGreaterThanOrEqualTo(prev.startChar());
            }
        }
    }

    @Test
    void data_length_is_a_multiple_of_five() {
        List<Integer> data = compute("TO square :size\n  FD :size\nEND\n").getData();
        assertThat(data.size() % 5).isZero();
    }

    // --- helpers -----------------------------------------------------------------

    private static SemanticTokens compute(String src) {
        ParsedDocument doc = ParsedDocument.parse("file:///t.logo", src, BUILTINS);
        return SemanticTokensProvider.compute(doc, BUILTINS);
    }

    private static int index(String typeName) {
        int i = SemanticTokensProvider.TOKEN_TYPES.indexOf(typeName);
        if (i < 0) throw new IllegalStateException("unknown type " + typeName);
        return i;
    }

    private record DecodedToken(int line, int startChar, int length, int typeIdx, int modifiers) {}

    private static List<DecodedToken> decode(SemanticTokens tokens) {
        List<Integer> data = tokens.getData();
        List<DecodedToken> out = new ArrayList<>(data.size() / 5);
        int line = 0, ch = 0;
        for (int i = 0; i < data.size(); i += 5) {
            int dl = data.get(i);
            int dc = data.get(i + 1);
            line += dl;
            ch = (dl == 0) ? ch + dc : dc;
            out.add(new DecodedToken(line, ch, data.get(i + 2), data.get(i + 3), data.get(i + 4)));
        }
        return out;
    }
}
