package com.example.logolsp.server;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.logging.Logger;

/**
 * Phase 0 stub for workspace-scoped LSP notifications.
 *
 * <p>No workspace features are advertised yet, so these handlers merely log and return.
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
