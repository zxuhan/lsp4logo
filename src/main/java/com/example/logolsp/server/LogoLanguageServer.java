package com.example.logolsp.server;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LOGO language server.
 *
 * <p>Phase 0 wiring: this class completes the LSP {@code initialize} handshake with an empty
 * {@link ServerCapabilities} payload and delegates document/workspace events to stub services.
 * Feature capabilities (go-to-definition, semantic tokens, diagnostics, …) will be advertised
 * in later phases as their providers come online.
 *
 * <p>Lifecycle follows the LSP specification: a client sends {@code initialize}, optionally
 * {@code shutdown}, then {@code exit}. Per spec, {@code exit} without a prior {@code shutdown}
 * terminates the process with exit code {@code 1}; otherwise {@code 0}.
 */
public final class LogoLanguageServer implements LanguageServer, LanguageClientAware {

    private static final Logger LOGGER = Logger.getLogger(LogoLanguageServer.class.getName());

    private final LogoTextDocumentService textDocumentService = new LogoTextDocumentService();
    private final LogoWorkspaceService workspaceService = new LogoWorkspaceService();

    private volatile LanguageClient client;
    private volatile boolean shutdownRequested;

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        LOGGER.log(Level.INFO, "initialize from {0}",
                params.getClientInfo() != null ? params.getClientInfo().getName() : "<unknown>");
        ServerCapabilities capabilities = new ServerCapabilities();
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
        System.exit(code);
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

    /** Returns the connected language client, or {@code null} before {@link #connect} is called. */
    LanguageClient getClient() {
        return client;
    }
}
