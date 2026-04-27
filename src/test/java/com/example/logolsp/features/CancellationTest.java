package com.example.logolsp.features;

import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.document.ParsedDocument;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that every provider cooperates with LSP cancellation.
 *
 * <p>The test injects a {@link CancelChecker} that throws on first call, then asserts
 * the provider propagates the cancellation rather than running to completion. This is
 * the behaviour the LSP4J launcher relies on to abort superseded requests when the
 * client sends {@code $/cancelRequest} (e.g. when the user types a new keystroke
 * before a previous hover/completion has finished).
 */
class CancellationTest {

    private static final LogoBuiltins BUILTINS = LogoBuiltins.loadDefault();
    private static final String URI = "file:///t.logo";
    private static final String SRC = "TO square :size\n  REPEAT 4 [ FD :size RT 90 ]\nEND\nsquare 60\n";

    private static final CancelChecker ALWAYS_CANCEL = () -> {
        throw new java.util.concurrent.CancellationException("cancelled");
    };

    @Test
    void definition_propagates_cancellation() {
        ParsedDocument doc = ParsedDocument.parse(URI, SRC, BUILTINS);
        assertThatThrownBy(() -> DefinitionProvider.definition(doc, new Position(1, 18), ALWAYS_CANCEL))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void hover_propagates_cancellation() {
        ParsedDocument doc = ParsedDocument.parse(URI, SRC, BUILTINS);
        assertThatThrownBy(() -> HoverProvider.hover(doc, BUILTINS, new Position(1, 18), ALWAYS_CANCEL))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void completion_propagates_cancellation() {
        ParsedDocument doc = ParsedDocument.parse(URI, SRC, BUILTINS);
        assertThatThrownBy(() -> CompletionProvider.completion(doc, BUILTINS, new Position(1, 0), ALWAYS_CANCEL))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void semantic_tokens_propagates_cancellation() {
        ParsedDocument doc = ParsedDocument.parse(URI, SRC, BUILTINS);
        assertThatThrownBy(() -> SemanticTokensProvider.compute(doc, BUILTINS, ALWAYS_CANCEL))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void document_symbols_propagates_cancellation() {
        ParsedDocument doc = ParsedDocument.parse(URI, SRC, BUILTINS);
        assertThatThrownBy(() -> DocumentSymbolProvider.documentSymbols(doc, ALWAYS_CANCEL))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }
}
