package com.example.logolsp.server;

import com.example.logolsp.document.DocumentStore;
import com.example.logolsp.document.ParsedDocument;
import com.example.logolsp.features.CompletionProvider;
import com.example.logolsp.features.DefinitionProvider;
import com.example.logolsp.features.DocumentSymbolProvider;
import com.example.logolsp.features.HoverProvider;
import com.example.logolsp.features.SemanticTokensProvider;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Handles text-document lifecycle notifications.
 *
 * <p>With {@link org.eclipse.lsp4j.TextDocumentSyncKind#Full Full} sync, every
 * {@code didChange} carries the complete new text in the last change event. We reparse
 * from scratch and publish fresh diagnostics — simple and correct for LOGO-sized files
 * (see ADR-005).
 *
 * <p>{@code didClose} clears diagnostics by publishing an empty list, matching the LSP
 * recommendation so the client doesn't leave stale squiggles on a closed document.
 */
public final class LogoTextDocumentService implements TextDocumentService {

    private static final Logger LOGGER = Logger.getLogger(LogoTextDocumentService.class.getName());

    private final DocumentStore documentStore;
    private final Supplier<LanguageClient> clientSupplier;

    LogoTextDocumentService(DocumentStore documentStore, Supplier<LanguageClient> clientSupplier) {
        this.documentStore = Objects.requireNonNull(documentStore, "documentStore");
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String text = params.getTextDocument().getText();
        LOGGER.fine(() -> "didOpen " + uri);
        ParsedDocument doc = documentStore.openOrReplace(uri, text);
        publishDiagnostics(doc);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
        if (changes == null || changes.isEmpty()) return;
        // Full sync: the last change carries the complete new text.
        String newText = changes.get(changes.size() - 1).getText();
        if (newText == null) return;
        LOGGER.fine(() -> "didChange " + uri);
        ParsedDocument doc = documentStore.openOrReplace(uri, newText);
        publishDiagnostics(doc);
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        LOGGER.fine(() -> "didClose " + uri);
        documentStore.close(uri);
        LanguageClient client = clientSupplier.get();
        if (client != null) {
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        LOGGER.fine(() -> "didSave " + params.getTextDocument().getUri());
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
            definition(DefinitionParams params) {
        String uri = params.getTextDocument().getUri();
        return CompletableFuture.supplyAsync(() -> {
            ParsedDocument doc = documentStore.get(uri).orElse(null);
            if (doc == null) return Either.forLeft(List.of());
            List<Location> locs = DefinitionProvider.definition(doc, params.getPosition());
            return Either.forLeft(locs);
        });
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        String uri = params.getTextDocument().getUri();
        return CompletableFuture.supplyAsync(() -> {
            ParsedDocument doc = documentStore.get(uri).orElse(null);
            if (doc == null) return new SemanticTokens(List.of());
            return SemanticTokensProvider.compute(doc, documentStore.builtins());
        });
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        String uri = params.getTextDocument().getUri();
        return CompletableFuture.supplyAsync(() -> {
            ParsedDocument doc = documentStore.get(uri).orElse(null);
            if (doc == null) return Either.forLeft(List.of());
            List<CompletionItem> items = CompletionProvider.completion(
                    doc, documentStore.builtins(), params.getPosition());
            return Either.forLeft(items);
        });
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        String uri = params.getTextDocument().getUri();
        return CompletableFuture.supplyAsync(() -> {
            ParsedDocument doc = documentStore.get(uri).orElse(null);
            if (doc == null) return null;
            return HoverProvider.hover(doc, documentStore.builtins(), params.getPosition());
        });
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>>
            documentSymbol(DocumentSymbolParams params) {
        String uri = params.getTextDocument().getUri();
        return CompletableFuture.supplyAsync(() -> {
            ParsedDocument doc = documentStore.get(uri).orElse(null);
            if (doc == null) return List.of();
            return DocumentSymbolProvider.documentSymbols(doc).stream()
                    .map(Either::<SymbolInformation, DocumentSymbol>forRight)
                    .toList();
        });
    }

    private void publishDiagnostics(ParsedDocument doc) {
        LanguageClient client = clientSupplier.get();
        if (client == null) return;
        client.publishDiagnostics(new PublishDiagnosticsParams(doc.uri(), doc.diagnostics()));
    }
}
