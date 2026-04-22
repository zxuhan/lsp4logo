package com.example.logolsp.server;

import com.example.logolsp.builtins.LogoBuiltins;
import com.example.logolsp.document.DocumentStore;
import com.example.logolsp.features.SemanticTokensProvider;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.List;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LOGO language server.
 *
 * <p>Advertises {@link TextDocumentSyncKind#Full} (the client sends complete text on every
 * change). Additional capabilities — semantic tokens, definition, completion, hover,
 * diagnostics delivery, document symbols — will be turned on in their respective phases.
 *
 * <h2>Lifecycle</h2>
 * Follows the LSP spec: {@code initialize} → {@code initialized} → … → {@code shutdown} →
 * {@code exit}. Per spec, {@code exit} without a prior {@code shutdown} terminates with
 * code {@code 1}; otherwise {@code 0}. The termination action is delegated to an
 * injected {@link Consumer} so tests can drive the server in-process without
 * {@code System.exit}ing the test JVM.
 */
public final class LogoLanguageServer implements LanguageServer, LanguageClientAware {

    private static final Logger LOGGER = Logger.getLogger(LogoLanguageServer.class.getName());

    private final DocumentStore documentStore;
    private final LogoTextDocumentService textDocumentService;
    private final LogoWorkspaceService workspaceService;
    private final Consumer<Integer> onExit;

    private volatile LanguageClient client;
    private volatile boolean shutdownRequested;

    /** Production constructor: exits the JVM on {@code exit}. */
    public LogoLanguageServer() {
        this(new DocumentStore(LogoBuiltins.loadDefault()), System::exit);
    }

    /** Test-friendly constructor. */
    LogoLanguageServer(DocumentStore documentStore, Consumer<Integer> onExit) {
        this.documentStore = Objects.requireNonNull(documentStore, "documentStore");
        this.onExit = Objects.requireNonNull(onExit, "onExit");
        this.textDocumentService = new LogoTextDocumentService(documentStore, this::getClient);
        this.workspaceService = new LogoWorkspaceService();
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        LOGGER.log(Level.INFO, "initialize from {0}",
                params.getClientInfo() != null ? params.getClientInfo().getName() : "<unknown>");
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        capabilities.setDefinitionProvider(Boolean.TRUE);

        SemanticTokensWithRegistrationOptions semantic = new SemanticTokensWithRegistrationOptions();
        semantic.setLegend(SemanticTokensProvider.legend());
        semantic.setFull(Boolean.TRUE);
        semantic.setRange(Boolean.FALSE);
        capabilities.setSemanticTokensProvider(semantic);

        CompletionOptions completion = new CompletionOptions();
        completion.setTriggerCharacters(List.of(":"));
        capabilities.setCompletionProvider(completion);

        ServerInfo info = new ServerInfo("logo-lsp", "0.1.0");
        return CompletableFuture.completedFuture(new InitializeResult(capabilities, info));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        LOGGER.info("shutdown requested");
        shutdownRequested = true;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        int code = shutdownRequested ? 0 : 1;
        LOGGER.log(Level.INFO, "exit (code={0})", code);
        onExit.accept(code);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    /** The connected language client, or {@code null} before {@link #connect} is called. */
    LanguageClient getClient() {
        return client;
    }
}
