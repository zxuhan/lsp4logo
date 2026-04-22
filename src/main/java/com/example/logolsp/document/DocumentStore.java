package com.example.logolsp.document;

import com.example.logolsp.builtins.LogoBuiltins;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe map from document URI to the latest {@link ParsedDocument}.
 *
 * <p>Mutations ({@code openOrReplace}, {@code close}) run under the
 * {@link ConcurrentHashMap} bucket lock only; parsing is done <em>before</em>
 * the map update so readers never observe a partially-updated document. A reader
 * racing with a writer simply sees the previous snapshot — the expected LSP
 * semantics (the client will re-query on the next change).
 */
public final class DocumentStore {

    private final ConcurrentMap<String, ParsedDocument> documents = new ConcurrentHashMap<>();
    private final LogoBuiltins builtins;

    public DocumentStore(LogoBuiltins builtins) {
        this.builtins = Objects.requireNonNull(builtins, "builtins");
    }

    /** Parses {@code text} and stores it under {@code uri}, replacing any prior entry. */
    public ParsedDocument openOrReplace(String uri, String text) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(text, "text");
        ParsedDocument doc = ParsedDocument.parse(uri, text, builtins);
        documents.put(uri, doc);
        return doc;
    }

    public Optional<ParsedDocument> get(String uri) {
        if (uri == null) return Optional.empty();
        return Optional.ofNullable(documents.get(uri));
    }

    public void close(String uri) {
        if (uri == null) return;
        documents.remove(uri);
    }
}
