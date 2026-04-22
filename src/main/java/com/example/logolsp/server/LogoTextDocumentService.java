package com.example.logolsp.server;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.util.logging.Logger;

/**
 * Phase 0 stub for text-document lifecycle notifications.
 *
 * <p>Logs each event and takes no other action. Phase 4 replaces this with a real document
 * store that caches parsed representations per URI.
 */
public final class LogoTextDocumentService implements TextDocumentService {

    private static final Logger LOGGER = Logger.getLogger(LogoTextDocumentService.class.getName());

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        LOGGER.fine(() -> "didOpen " + params.getTextDocument().getUri());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        LOGGER.fine(() -> "didChange " + params.getTextDocument().getUri());
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        LOGGER.fine(() -> "didClose " + params.getTextDocument().getUri());
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        LOGGER.fine(() -> "didSave " + params.getTextDocument().getUri());
    }
}
