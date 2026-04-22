package com.example.logolsp.server;

import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.document.DocumentStore;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test for the LOGO language server.
 *
 * <p>Starts the server in-process, wires it to a mock client via piped streams, and
 * verifies the LSP handshake plus the didOpen/didChange/didClose → publishDiagnostics
 * flow. This is the test that proves all the pieces connect — lexer, parser, analyzer,
 * document store, and the LSP4J JSON-RPC plumbing.
 */
class LogoLanguageServerIntegrationTest {

    private static final int PIPE_BUFFER_BYTES = 1 << 16;
    private static final long AWAIT_MILLIS = 5000;

    private PipedInputStream serverIn;
    private PipedOutputStream clientOut;
    private PipedInputStream clientIn;
    private PipedOutputStream serverOut;

    private ExecutorService executor;
    private Future<?> serverFuture;
    private Future<?> clientFuture;

    private LogoLanguageServer server;
    private CapturingClient client;
    private LanguageServer proxy;

    @BeforeEach
    void setup() throws IOException {
        serverIn = new PipedInputStream(PIPE_BUFFER_BYTES);
        clientOut = new PipedOutputStream(serverIn);
        clientIn = new PipedInputStream(PIPE_BUFFER_BYTES);
        serverOut = new PipedOutputStream(clientIn);

        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "lsp-integ");
            t.setDaemon(true);
            return t;
        });

        server = new LogoLanguageServer(
                new DocumentStore(LogoBuiltins.loadDefault()),
                code -> { /* suppress System.exit in test */ });
        Launcher<LanguageClient> serverLauncher = new LSPLauncher.Builder<LanguageClient>()
                .setLocalService(server)
                .setRemoteInterface(LanguageClient.class)
                .setInput(serverIn)
                .setOutput(serverOut)
                .setExecutorService(executor)
                .create();
        server.connect(serverLauncher.getRemoteProxy());
        serverFuture = serverLauncher.startListening();

        client = new CapturingClient();
        Launcher<LanguageServer> clientLauncher = new LSPLauncher.Builder<LanguageServer>()
                .setLocalService(client)
                .setRemoteInterface(LanguageServer.class)
                .setInput(clientIn)
                .setOutput(clientOut)
                .setExecutorService(executor)
                .create();
        proxy = clientLauncher.getRemoteProxy();
        clientFuture = clientLauncher.startListening();
    }

    @AfterEach
    void teardown() throws Exception {
        try {
            if (proxy != null) proxy.shutdown().get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) { /* best effort */ }
        try {
            if (proxy != null) proxy.exit();
        } catch (Exception ignored) { /* best effort */ }
        closeStream(clientOut);
        closeStream(serverIn);
        closeStream(serverOut);
        closeStream(clientIn);
        if (serverFuture != null) serverFuture.cancel(true);
        if (clientFuture != null) clientFuture.cancel(true);
        if (executor != null) {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static void closeStream(AutoCloseable c) {
        try { if (c != null) c.close(); } catch (Exception ignored) {}
    }

    @Test
    void initialize_returns_serverInfo_and_full_sync_capability() throws Exception {
        InitializeResult result = proxy.initialize(new InitializeParams())
                .get(AWAIT_MILLIS, TimeUnit.MILLISECONDS);
        assertThat(result.getServerInfo().getName()).isEqualTo("logo-lsp");
        Either<TextDocumentSyncKind, ?> sync = result.getCapabilities().getTextDocumentSync();
        assertThat(sync).isNotNull();
        assertThat(sync.isLeft()).isTrue();
        assertThat(sync.getLeft()).isEqualTo(TextDocumentSyncKind.Full);
        assertThat(result.getCapabilities().getDefinitionProvider().getLeft()).isTrue();
    }

    @Test
    void textDocument_definition_round_trip_finds_the_procedure_def() throws Exception {
        proxy.initialize(new InitializeParams()).get(AWAIT_MILLIS, TimeUnit.MILLISECONDS);
        String src = "TO greet\n  FD 10\nEND\ngreet\n";
        TextDocumentItem doc = new TextDocumentItem("file:///t.logo", "logo", 1, src);
        proxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(doc));
        client.awaitDiagnostics();

        // Click on the call site "greet" at line 3, column 2.
        DefinitionParams params = new DefinitionParams(
                new TextDocumentIdentifier("file:///t.logo"), new Position(3, 2));
        Either<List<? extends Location>, List<? extends LocationLink>> result =
                proxy.getTextDocumentService().definition(params)
                        .get(AWAIT_MILLIS, TimeUnit.MILLISECONDS);
        assertThat(result.isLeft()).isTrue();
        List<? extends Location> locations = result.getLeft();
        assertThat(locations).hasSize(1);
        Location loc = locations.get(0);
        assertThat(loc.getUri()).isEqualTo("file:///t.logo");
        // Definition is on line 0 (the "greet" after "TO ").
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(0);
    }

    @Test
    void didOpen_publishes_diagnostics_for_unknown_procedure() throws Exception {
        proxy.initialize(new InitializeParams()).get(AWAIT_MILLIS, TimeUnit.MILLISECONDS);

        TextDocumentItem doc = new TextDocumentItem("file:///bad.logo", "logo", 1, "HAMBURGER 1\n");
        proxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(doc));

        PublishDiagnosticsParams d = client.awaitDiagnostics();
        assertThat(d.getUri()).isEqualTo("file:///bad.logo");
        assertThat(d.getDiagnostics())
                .anyMatch(x -> x.getMessage().contains("unknown procedure"));
    }

    @Test
    void didChange_republishes_diagnostics_for_the_new_text() throws Exception {
        proxy.initialize(new InitializeParams()).get(AWAIT_MILLIS, TimeUnit.MILLISECONDS);

        TextDocumentItem doc = new TextDocumentItem("file:///t.logo", "logo", 1, "FD 100\n");
        proxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(doc));
        PublishDiagnosticsParams clean = client.awaitDiagnostics();
        assertThat(clean.getDiagnostics()).isEmpty();

        VersionedTextDocumentIdentifier id = new VersionedTextDocumentIdentifier("file:///t.logo", 2);
        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent("TO foo\n  FD :oops\nEND\n");
        proxy.getTextDocumentService().didChange(new DidChangeTextDocumentParams(id, List.of(change)));

        PublishDiagnosticsParams broken = client.awaitDiagnostics();
        assertThat(broken.getDiagnostics())
                .anyMatch(x -> x.getMessage().contains("undefined variable"));
    }

    @Test
    void didClose_publishes_empty_diagnostics_to_clear_the_client() throws Exception {
        proxy.initialize(new InitializeParams()).get(AWAIT_MILLIS, TimeUnit.MILLISECONDS);

        TextDocumentItem doc = new TextDocumentItem("file:///t.logo", "logo", 1, "HAMBURGER 1\n");
        proxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(doc));
        client.awaitDiagnostics(); // drain first publish

        proxy.getTextDocumentService().didClose(new DidCloseTextDocumentParams(
                new TextDocumentIdentifier("file:///t.logo")));
        PublishDiagnosticsParams cleared = client.awaitDiagnostics();
        assertThat(cleared.getUri()).isEqualTo("file:///t.logo");
        assertThat(cleared.getDiagnostics()).isEmpty();
    }

    // --- mock client --------------------------------------------------------------

    static final class CapturingClient implements LanguageClient {
        private final BlockingQueue<PublishDiagnosticsParams> diagnosticsQueue = new LinkedBlockingQueue<>();

        @Override public void telemetryEvent(Object object) {}

        @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            diagnosticsQueue.offer(diagnostics);
        }

        @Override public void showMessage(MessageParams messageParams) {}

        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public void logMessage(MessageParams message) {}

        PublishDiagnosticsParams awaitDiagnostics() throws InterruptedException {
            PublishDiagnosticsParams p = diagnosticsQueue.poll(AWAIT_MILLIS, TimeUnit.MILLISECONDS);
            if (p == null) throw new AssertionError("no publishDiagnostics within " + AWAIT_MILLIS + "ms");
            return p;
        }
    }
}
