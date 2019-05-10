package proxy;

import game.Game;
import game.NetworkMode;
import packets.DataReader;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

public class ProxyServer {
    int portRemote;
    int portLocal;
    String host;

    public ProxyServer(int portRemote, int portLocal, String host) {
        this.portRemote = portRemote;
        this.portLocal = portLocal;
        this.host = host;
    }

    /**
     * runs a single-threaded proxy server on
     * the specified local port. It never returns.
     */
    public void runServer(DataReader onServerBoundPacket, DataReader onClientBoundPacket, EncryptionManager encryptionManager) {
        System.out.println("Starting proxy for " + host + ":" + portRemote + " on port " + portLocal);

        // Create a ServerSocket to listen for connections with
        AtomicReference<ServerSocket> ss = new AtomicReference<>();
        attempt(() -> ss.set(new ServerSocket(portLocal)), (ex) -> {
            ex.printStackTrace();
            System.exit(1);
        });

        final byte[] request = new byte[4096];
        final byte[] reply = new byte[4096];

        while (true) {
            AtomicReference<Socket> client = new AtomicReference<>();
            AtomicReference<Socket> server = new AtomicReference<>();

            attempt(() -> {
                // Wait for a connection on the local port
                client.set(ss.get().accept());

                final InputStream streamFromClient = client.get().getInputStream();
                final OutputStream streamToClient = client.get().getOutputStream();
                encryptionManager.setStreamToClient(streamToClient);

                // If the server cannot connect, close client connection
                attempt(() -> server.set(new Socket(host, portRemote)), (ex) -> {
                    System.out.println("Cannot connect to host: ");
                    ex.printStackTrace();

                    attempt(client.get()::close);
                });

                final InputStream streamFromServer = server.get().getInputStream();
                final OutputStream streamToServer = server.get().getOutputStream();
                encryptionManager.setStreamToServer(streamToServer);

                new Thread(() -> {
                    Game.setMode(NetworkMode.HANDSHAKE);
                    attempt(() -> {
                        int bytesRead;
                        while ((bytesRead = streamFromClient.read(request)) != -1) {

                            System.out.println("Read bytes from client: " + bytesRead);
                            onServerBoundPacket.pushData(request, bytesRead);
                        }
                    });
                    // the client closed the connection to us, so close our connection to the server.
                    attempt(streamToServer::close);
                }).start();

                attempt(() -> {
                    int bytesRead;
                    while ((bytesRead = streamFromServer.read(reply)) != -1) {
                        System.out.println("Read bytes from server: " + bytesRead);
                        onClientBoundPacket.pushData(reply, bytesRead);
                    }
                }, (ex) -> {
                    ex.printStackTrace();
                    System.out.println("Client probably disconnected. Waiting for new connection...");
                });

                // The server closed its connection to us, so we close our connection to our client.
                streamToClient.close();
            }, (ex) -> {
                if (server.get() != null) attempt(server.get()::close);
                if (client.get() != null) attempt(client.get()::close);
            });
        }
    }

    public static void attempt(IExceptionHandler r) {
        attempt(r, Throwable::printStackTrace);
    }

    public static void attempt(IExceptionHandler r, IExceptionConsumer failure) {
        try {
            r.run();
        } catch (Exception ex) {
            failure.consume(ex);
        }
    }
}
