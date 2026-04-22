package com.example.logolsp;

import com.example.logolsp.server.LogoLanguageServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.Channels;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Entry point for the LOGO language server.
 *
 * <p>By default the server speaks LSP over stdin/stdout, the LSP-standard transport. Passing
 * {@code --socket <port>} instead binds a TCP listener on {@code 127.0.0.1:<port>} and accepts a
 * single connection — useful when attaching a debugger or driving the server from an
 * integration test.
 *
 * <p>All logging is routed to stderr because LSP reserves stdout for its JSON-RPC framing.
 */
public final class Main {

    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    private Main() {}

    public static void main(String[] args) throws Exception {
        configureStderrLogging();
        OptionalInt socketPort = parseSocketPort(args);
        if (socketPort.isPresent()) {
            runSocket(socketPort.getAsInt());
        } else {
            runStdio();
        }
    }

    private static OptionalInt parseSocketPort(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (!"--socket".equals(args[i])) continue;
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("--socket requires a port number");
            }
            int port;
            try {
                port = Integer.parseInt(args[i + 1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--socket port is not a number: " + args[i + 1], e);
            }
            if (port <= 0 || port > 0xFFFF) {
                throw new IllegalArgumentException("--socket port out of range: " + port);
            }
            return OptionalInt.of(port);
        }
        return OptionalInt.empty();
    }

    private static void runStdio() {
        LOGGER.info("Starting LOGO LSP over stdio");
        launch(System.in, System.out);
    }

    private static void runSocket(int port) throws Exception {
        try (AsynchronousServerSocketChannel server =
                     AsynchronousServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", port))) {
            LOGGER.info(() -> "LOGO LSP listening on tcp://127.0.0.1:" + port);
            AsynchronousSocketChannel client = server.accept().get();
            launch(Channels.newInputStream(client), Channels.newOutputStream(client));
        }
    }

    private static void launch(InputStream in, OutputStream out) {
        LogoLanguageServer server = new LogoLanguageServer();
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);
        server.connect(launcher.getRemoteProxy());
        try {
            launcher.startListening().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "LSP listener interrupted", e);
        } catch (ExecutionException e) {
            LOGGER.log(Level.SEVERE, "LSP listener terminated with error", e);
        }
    }

    private static void configureStderrLogging() {
        Logger root = LogManager.getLogManager().getLogger("");
        for (Handler h : root.getHandlers()) {
            root.removeHandler(h);
        }
        ConsoleHandler stderr = new ConsoleHandler(); // writes to System.err by default
        stderr.setLevel(Level.ALL);
        root.addHandler(stderr);
        root.setLevel(Level.INFO);
    }
}
