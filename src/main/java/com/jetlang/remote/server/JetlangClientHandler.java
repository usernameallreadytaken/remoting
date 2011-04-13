package com.jetlang.remote.server;

import com.jetlang.remote.core.*;
import org.jetlang.fibers.Fiber;
import org.jetlang.fibers.ThreadFiber;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class JetlangClientHandler implements Acceptor.ClientHandler, ClientPublisher {

    private final SerializerFactory ser;
    private final JetlangSessionChannels channels;
    private final Executor exec;
    private final JetlangSessionConfig config;
    private final FiberFactory fiberFactory;
    private final ClientErrorHandler errorHandler;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final HashSet<ClientTcpSocket> clients = new HashSet<ClientTcpSocket>();
    private final Charset charset = Charset.forName("US-ASCII");
    private final Fiber globalSendFiber;
    private final ObjectByteWriter writer;

    public static interface FiberFactory {

        Fiber createGlobalSendFiber();

        Fiber createSendFiber(Socket socket);

        public static class ThreadFiberFactory implements FiberFactory {

            public Fiber createGlobalSendFiber() {
                return new ThreadFiber();
            }

            public Fiber createSendFiber(Socket socket) {
                return new ThreadFiber();
            }
        }
    }

    public static interface ClientErrorHandler {

        void onClientException(Exception e);

        public static class SysOutClientErrorHandler implements ClientErrorHandler {
            public void onClientException(Exception e) {
                e.printStackTrace();
            }
        }
    }

    public JetlangClientHandler(SerializerFactory ser,
                                JetlangSessionChannels channels,
                                Executor exec,
                                JetlangSessionConfig config,
                                FiberFactory fiberFactory,
                                ClientErrorHandler errorHandler) {
        this.ser = ser;
        this.channels = channels;
        this.exec = exec;
        this.config = config;
        this.fiberFactory = fiberFactory;
        this.errorHandler = errorHandler;
        this.globalSendFiber = fiberFactory.createGlobalSendFiber();
        this.globalSendFiber.start();
        this.writer = ser.createForGlobalWriter();
    }

    public void startClient(final Socket socket) {
        ClientTcpSocket client = new ClientTcpSocket(new TcpSocket(socket));
        synchronized (clients) {
            if (running.get()) {
                clients.add(client);
            } else {
                stop(client);
                return;
            }
        }
        try {
            Runnable clientReader = createRunnable(client);
            exec.execute(clientReader);
        } catch (IOException e) {
            stop(client);
        }
    }

    public void close() {
        globalSendFiber.dispose();
        synchronized (clients) {
            if (running.compareAndSet(true, false)) {
                for (ClientTcpSocket client : clients) {
                    stop(client);
                }
            }
        }
    }

    private void stop(ClientTcpSocket client) {
        client.close();
        synchronized (clients) {
            clients.remove(client);
        }
    }

    public int clientCount() {
        synchronized (clients) {
            return clients.size();
        }
    }

    CloseableByteArrayStream globalBuffer = new CloseableByteArrayStream();
    SocketMessageStreamWriter stream = createStream();

    private SocketMessageStreamWriter createStream() {
        try {
            return new SocketMessageStreamWriter(globalBuffer, charset, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void publishToAllSubscribedClients(final String topic, final Object msg) {
        Runnable toSend = new Runnable() {
            public void run() {
                globalBuffer.reset();
                try {
                    stream.write(topic, msg);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                synchronized (clients) {
                    for (ClientTcpSocket client : clients) {
                        client.publishIfSubscribed(topic, globalBuffer.data.toByteArray());
                    }
                }
            }
        };
        globalSendFiber.execute(toSend);
    }


    private Runnable createRunnable(final ClientTcpSocket clientTcpSocket) throws IOException {
        final TcpSocket socket = clientTcpSocket.getSocket();
        final Fiber fiber = fiberFactory.createSendFiber(socket.getSocket());
        final Serializer serializer = ser.createForSocket(socket.getSocket());
        configureClientSocketAfterAccept(socket.getSocket());
        final JetlangStreamSession session = new JetlangStreamSession(socket.getId(), new SocketMessageStreamWriter(socket, charset, serializer.getWriter()), fiber);
        return new Runnable() {
            public void run() {
                try {
                    final Runnable onReadTimeout = new Runnable() {
                        public void run() {
                            session.ReadTimeout.publish(new ReadTimeoutEvent());
                        }
                    };
                    final StreamReader input = new StreamReader(socket.getInputStream(), charset, serializer.getReader(), onReadTimeout);
                    clientTcpSocket.setSession(session);
                    channels.publishNewSession(session);
                    session.startHeartbeat(config.getHeartbeatIntervalInMs(), TimeUnit.MILLISECONDS);
                    fiber.start();
                    while (readFromStream(input, session)) {

                    }
                } catch (IOException disconnect) {
                    //failed.printStackTrace();
                } catch (Exception clientFailure) {
                    errorHandler.onClientException(clientFailure);
                } finally {
                    fiber.dispose();
                    stop(clientTcpSocket);
                    session.SessionClose.publish(new SessionCloseEvent());
                }
            }
        };
    }

    private void configureClientSocketAfterAccept(Socket socket) throws SocketException {
        socket.setTcpNoDelay(config.getTcpNoDelay());
        if (config.getReceiveBufferSize() > 0)
            socket.setReceiveBufferSize(config.getReceiveBufferSize());
        if (config.getSendBufferSize() > 0)
            socket.setSendBufferSize(config.getSendBufferSize());
        if (config.getReadTimeoutInMs() > 0)
            socket.setSoTimeout(config.getReadTimeoutInMs());
    }

    private boolean readFromStream(StreamReader input, JetlangStreamSession session) throws IOException {
        int read = input.readByteAsInt();
        if (read < 0) {
            return false;
        }
        switch (read) {
            case MsgTypes.Heartbeat:
                session.onHb();
                break;
            case MsgTypes.Subscription:
                int topicSizeInBytes = input.readByteAsInt();
                String topic = input.readString(topicSizeInBytes);
                session.onSubscriptionRequest(topic);
                break;
            case MsgTypes.Unsubscribe:
                int unsubSize = input.readByteAsInt();
                String top = input.readString(unsubSize);
                session.onUnsubscribeRequest(top);
                break;
            case MsgTypes.Disconnect:
                session.write(MsgTypes.Disconnect);
                session.onLogout();
                break;
            case MsgTypes.Data:
                int topicSize = input.readByteAsInt();
                String msgTopic = input.readString(topicSize);
                int msgSize = input.readInt();
                Object msg = input.readObject(msgTopic, msgSize);
                session.onMessage(msgTopic, msg);
                break;
            default:
                System.err.println("Unknown message type: " + read);
                return false;
        }
        return true;
    }

}
