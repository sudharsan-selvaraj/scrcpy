package com.genymobile.scrcpy;

import android.media.MediaCodecInfo;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.java_websocket.WebSocket;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class WebSocketConnection extends Connection {
    private static final byte[] MAGIC_BYTES_INITIAL = "scrcpy_initial".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MAGIC_BYTES_MESSAGE = "scrcpy_message".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DEVICE_NAME_BYTES = Device.getDeviceName().getBytes(StandardCharsets.UTF_8);
    private final WSServer wsServer;
    private final HashSet<WebSocket> sockets = new HashSet<>();
    private ScreenEncoder screenEncoder;

    public WebSocketConnection(Options options, VideoSettings videoSettings, WSServer wsServer) {
        super(options, videoSettings);
        this.wsServer = wsServer;
    }

    public void join(WebSocket webSocket, VideoSettings videoSettings) {
        sockets.add(webSocket);
        boolean changed = setVideoSettings(videoSettings);
        wsServer.sendInitialInfoToAll();
        if (!Device.isScreenOn()) {
            controller.turnScreenOn();
        }
        if (screenEncoder == null || !screenEncoder.isAlive()) {
            Ln.d("First connection. Start new encoder.");
            device.setRotationListener(this);
            screenEncoder = new ScreenEncoder(videoSettings);
            screenEncoder.start(device, this);
        } else {
            if (!changed) {
                if (this.streamInvalidateListener != null) {
                    streamInvalidateListener.onStreamInvalidate();
                }
            }
        }
    }

    public void leave(WebSocket webSocket) {
        sockets.remove(webSocket);
        if (sockets.isEmpty()) {
            Ln.d("Last client has left");
            this.release();
        }
        //wsServer.sendInitialInfoToAll();
    }

    public static ByteBuffer deviceMessageToByteBuffer(DeviceMessage msg) {
        ByteBuffer buffer = ByteBuffer.wrap(msg.writeToByteArray(MAGIC_BYTES_MESSAGE.length));
        buffer.put(MAGIC_BYTES_MESSAGE);
        buffer.rewind();
        return buffer;
    }


    @Override
    public void send(DeviceMessage msg) {
        if (sockets.isEmpty()) {
            return;
        }
        synchronized (sockets) {
            for (WebSocket webSocket : sockets) {
                WSServer.SocketInfo info = webSocket.getAttachment();
                if (!webSocket.isOpen() || info == null) {
                    continue;
                }
                ObjectMapper mapper = new ObjectMapper();
                try {
                    String json = mapper.writeValueAsString(msg);
                    webSocket.send(json);
                } catch (Exception e) {
                    Ln.e(e.getMessage());
                }

            }
        }
    }

    @Override
    void send(ByteBuffer data) {
        if (sockets.isEmpty()) {
            return;
        }
        synchronized (sockets) {
            for (WebSocket webSocket : sockets) {
                WSServer.SocketInfo info = webSocket.getAttachment();
                if (!webSocket.isOpen() || info == null) {
                    continue;
                }
                webSocket.send(data);
            }
        }
    }

    public static void sendInitialInfo(HashMap initialInfo, WebSocket webSocket, int clientId) {
        initialInfo.put("clientId", clientId);
        ObjectMapper mapper = new ObjectMapper();
        try {
            webSocket.send(mapper.writeValueAsString(initialInfo));
        } catch (Exception e) {}
    }

    public void sendDeviceMessage(DeviceMessage msg) {
        //ByteBuffer buffer = deviceMessageToByteBuffer(msg);
        send(msg);
    }

    @Override
    public boolean hasConnections() {
        return sockets.size() > 0;
    }

    @Override
    public void close() throws Exception {
//        wsServer.stop();
    }

    public VideoSettings getVideoSettings() {
        return videoSettings;
    }

    public Controller getController() {
        return controller;
    }

    public Device getDevice() {
        return device;
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    public static HashMap<String, Object> getInitialInfo() {

        HashMap<String, Object> initialInfo = new HashMap<String, Object>();
        int[] displayIds = Device.getDisplayIds();
        HashMap<Integer, DisplayInfo> displayInfoHashMap = new HashMap<>();
        List<DisplayInfo> displays = new ArrayList<>();
        for (int displayId : displayIds) {
            DisplayInfo displayInfo = Device.getDisplayInfo(displayId);
            displayInfoHashMap.put(displayId, displayInfo);
            displays.add(displayInfo);
            WebSocketConnection connection = WSServer.getConnectionForDisplay(displayId);
            if(connection != null) {
                initialInfo.put("screenInfo", connection.getDevice().getScreenInfo());
            }
        }
        MediaCodecInfo[] encoders = ScreenEncoder.listEncoders();
        initialInfo.put("displays", displays);
        initialInfo.put("encoders", Arrays.asList(encoders));

        return initialInfo;
    }

    public void onRotationChanged(int rotation) {
        super.onRotationChanged(rotation);
        wsServer.sendInitialInfoToAll();
    }

    private void release() {

        WSServer.releaseConnectionForDisplay(this.videoSettings.getDisplayId());
        // encoder will stop itself after checking .hasConnections()
    }
}
