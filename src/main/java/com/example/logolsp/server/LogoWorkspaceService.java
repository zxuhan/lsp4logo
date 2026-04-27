package com.example.logolsp.server;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.logging.Logger;

/**
 * Workspace-scoped LSP notifications.
 *
 * <p>This server is single-file: no {@code workspace/symbol}, no cross-file resolution,
 * no file watchers. The handlers below merely log and return so the protocol stays
 * well-formed if a client sends them.
 */
public final class LogoWorkspaceService implements WorkspaceService {

    private static final Logger LOGGER = Logger.getLogger(LogoWorkspaceService.class.getName());

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        LOGGER.fine("didChangeConfiguration");
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        LOGGER.fine("didChangeWatchedFiles");
    }
}
