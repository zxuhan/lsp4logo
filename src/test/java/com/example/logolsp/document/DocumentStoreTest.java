package com.example.logolsp.document;

import com.example.logolsp.builtins.LogoBuiltins;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStoreTest {

    private static final LogoBuiltins BUILTINS = LogoBuiltins.loadDefault();

    @Test
    void get_returns_empty_for_unknown_uri() {
        DocumentStore store = new DocumentStore(BUILTINS);
        assertThat(store.get("file:///never.logo")).isEmpty();
        assertThat(store.get(null)).isEmpty();
    }

    @Test
    void openOrReplace_parses_and_stores_by_uri() {
        DocumentStore store = new DocumentStore(BUILTINS);
        ParsedDocument doc = store.openOrReplace("file:///a.logo", "FD 100\n");
        assertThat(doc.uri()).isEqualTo("file:///a.logo");
        assertThat(store.get("file:///a.logo")).contains(doc);
    }

    @Test
    void openOrReplace_swaps_in_a_fresh_parsed_document() {
        DocumentStore store = new DocumentStore(BUILTINS);
        store.openOrReplace("file:///a.logo", "FD 100");
        ParsedDocument second = store.openOrReplace("file:///a.logo", "HAMBURGER 1");
        assertThat(store.get("file:///a.logo")).contains(second);
        assertThat(second.diagnostics())
                .anyMatch(d -> d.getMessage().contains("unknown procedure"));
    }

    @Test
    void close_removes_the_entry() {
        DocumentStore store = new DocumentStore(BUILTINS);
        store.openOrReplace("file:///a.logo", "FD 100");
        store.close("file:///a.logo");
        assertThat(store.get("file:///a.logo")).isEmpty();
    }

    @Test
    void close_on_unknown_uri_is_a_noop() {
        DocumentStore store = new DocumentStore(BUILTINS);
        store.close("file:///never.logo");
        store.close(null);
    }
}
