package com.genymobile.scrcpy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class WSServer extends WebSocketServer {
    private static final String PID_FILE_PATH = "/data/local/tmp/ws_scrcpy.pid";

    public static final class SocketInfo {
        private static final HashSet<Short> INSTANCES_BY_ID = new HashSet<>();
        private final short id;
        private WebSocketConnection connection;

        SocketInfo(short id) {
            this.id = id;
            INSTANCES_BY_ID.add(id);
        }

        public static short getNextClientId() {
            short nextClientId = 0;
            while (INSTANCES_BY_ID.contains(++nextClientId)) {
                if (nextClientId == Short.MAX_VALUE) {
                    return -1;
                }
            }
            return nextClientId;
        }

        public short getId() {
            return id;
        }

        public WebSocketConnection getConnection() {
            return this.connection;
        }

        public void setConnection(WebSocketConnection connection) {
            this.connection = connection;
        }

        public void release() {
            INSTANCES_BY_ID.remove(id);
        }
    }

    protected final ControlMessageReaderJson reader = new ControlMessageReaderJson();
    private final Options options;
    private static final HashMap<Integer, WebSocketConnection> STREAM_BY_DISPLAY_ID = new HashMap<>();

    public WSServer(Options options) {
        super(new InetSocketAddress(options.getListenOnAllInterfaces() ? "0.0.0.0" : "127.0.0.1", options.getPortNumber()));
        this.options = options;
        unlinkPidFile();
    }

    @Override
    public void onOpen(WebSocket webSocket, ClientHandshake handshake) {
        if (webSocket.isOpen()) {
            short clientId = SocketInfo.getNextClientId();
            if (clientId == -1) {
                webSocket.close(CloseFrame.TRY_AGAIN_LATER);
                return;
            }
            SocketInfo info = new SocketInfo(clientId);
            webSocket.setAttachment(info);
            WebSocketConnection.sendInitialInfo(WebSocketConnection.getInitialInfo(), webSocket, clientId);
            Ln.d("Client entered the room!");
        }
    }

    @Override
    public void onClose(WebSocket webSocket, int code, String reason, boolean remote) {
        Ln.d("Client has left the room!");
        //FilePushHandler.cancelAllForConnection(webSocket);
        SocketInfo socketInfo = webSocket.getAttachment();
        if (socketInfo != null) {
            WebSocketConnection connection = socketInfo.getConnection();
            if (connection != null) {
                connection.leave(webSocket);
            }
            socketInfo.release();
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String message) {
        String address = webSocket.getRemoteSocketAddress().getAddress().getHostAddress();
        SocketInfo socketInfo = webSocket.getAttachment();
        if (socketInfo == null) {
            Ln.e("No info attached to connection");
            return;
        }
        Ln.i(message);
        WebSocketConnection connection = socketInfo.getConnection();
        ObjectReader objectReader = new ObjectMapper().readerFor(Map.class);
        try {
            Map<String, String> parseMessage = objectReader.readValue(message);
            if (parseMessage.get("message") != null && parseMessage.get("message").equals("start")) {
                VideoSettings videoSettings = new VideoSettings();
                videoSettings.setDisplayId(0);
                videoSettings.setSendFrameMeta(false);
                   joinStreamForDisplayId(webSocket, videoSettings, options, videoSettings.getDisplayId(), this);
                   return;
            } else {
                ControlMessage controlMessage = reader.parseEvent(parseMessage);
                if (connection != null) {
                    Controller controller = connection.getController();
                    controller.handleEvent(controlMessage);
                }
            }


        } catch (Exception e) {
            Ln.i(e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteBuffer message) {
//        SocketInfo socketInfo = webSocket.getAttachment();
//        if (socketInfo == null) {
//            Ln.e("No info attached to connection");
//            return;
//        }
//        WebSocketConnection connection = socketInfo.getConnection();
//        String address = webSocket.getRemoteSocketAddress().getAddress().getHostAddress();
//        ControlMessage controlMessage = reader.parseEvent(message);
//        if (controlMessage != null) {
//            if (controlMessage.getType() == ControlMessage.TYPE_PUSH_FILE) {
//                FilePushHandler.handlePush(webSocket, controlMessage);
//                return;
//            }
//            if (controlMessage.getType() == ControlMessage.TYPE_CHANGE_STREAM_PARAMETERS) {
//                VideoSettings videoSettings = controlMessage.getVideoSettings();
//                int displayId = videoSettings.getDisplayId();
//                if (connection != null) {
//                    if (connection.getVideoSettings().getDisplayId() != displayId) {
//                        connection.leave(webSocket);
//                    }
//                }
//                joinStreamForDisplayId(webSocket, videoSettings, options, displayId, this);
//                return;
//            }
//            if (connection != null) {
//                Controller controller = connection.getController();
//                controller.handleEvent(controlMessage);
//            }
//        } else {
//            Ln.w("?  Client from " + address + " sends bytes: " + message);
//        }
    }

    @Override
    public void onError(WebSocket webSocket, Exception ex) {
        Ln.e("WebSocket error", ex);
        if (webSocket != null) {
            // some errors like port binding failed may not be assignable to a specific websocket
            FilePushHandler.cancelAllForConnection(webSocket);
        }
        if (ex instanceof BindException) {
            System.exit(1);
        }
    }

    @Override
    public void onStart() {
        Ln.d("Server started! " + this.getAddress().toString());
        this.setConnectionLostTimeout(0);
        this.setConnectionLostTimeout(100);
        writePidFile();
    }

    private static void joinStreamForDisplayId(
            WebSocket webSocket, VideoSettings videoSettings, Options options, int displayId, WSServer wsServer) {
        SocketInfo socketInfo = webSocket.getAttachment();
        WebSocketConnection connection = STREAM_BY_DISPLAY_ID.get(displayId);
        if (connection == null) {
            connection = new WebSocketConnection(options, videoSettings, wsServer);
            STREAM_BY_DISPLAY_ID.put(displayId, connection);
        }
        socketInfo.setConnection(connection);
        connection.join(webSocket, videoSettings);
    }

    private static void unlinkPidFile() {
        try {
            File pidFile = new File(PID_FILE_PATH);
            if (pidFile.exists()) {
                if (!pidFile.delete()) {
                    Ln.e("Failed to delete PID file");
                }
            }
        } catch (Exception e) {
            Ln.e("Failed to delete PID file:", e);
        }
    }

    private static void writePidFile() {
        File file = new File(PID_FILE_PATH);
        FileOutputStream stream;
        try {
            stream = new FileOutputStream(file, false);
            stream.write(Integer.toString(android.os.Process.myPid()).getBytes(StandardCharsets.UTF_8));
            stream.close();
        } catch (IOException e) {
            Ln.e(e.getMessage());
        }
    }

    public static WebSocketConnection getConnectionForDisplay(int displayId) {
        return STREAM_BY_DISPLAY_ID.get(displayId);
    }

    public static void releaseConnectionForDisplay(int displayId) {
        STREAM_BY_DISPLAY_ID.get(displayId).getDevice().release();
        STREAM_BY_DISPLAY_ID.remove(displayId);
    }

    public void sendInitialInfoToAll() {
        Collection<WebSocket> webSockets = this.getConnections();
        if (webSockets.isEmpty()) {
            return;
        }
        HashMap<String, Object> initialInfo = WebSocketConnection.getInitialInfo();
        for (WebSocket webSocket : webSockets) {
            SocketInfo socketInfo = webSocket.getAttachment();
            if (socketInfo == null) {
                continue;
            }
            WebSocketConnection.sendInitialInfo(initialInfo, webSocket, socketInfo.getId());
        }
    }
}
