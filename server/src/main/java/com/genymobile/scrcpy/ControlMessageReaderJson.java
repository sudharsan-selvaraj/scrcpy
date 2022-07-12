package com.genymobile.scrcpy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class ControlMessageReaderJson {


    public ControlMessage parseEvent(Map<String, String> incommingMessage) {
        if(incommingMessage.get("type") == null) {
            return null;
        }
        Integer type = Integer.parseInt(incommingMessage.get("type"));
        ControlMessage msg;
        switch (type) {
            case ControlMessage.TYPE_INJECT_KEYCODE:
                msg = parseInjectKeycode(incommingMessage);
                break;
            case ControlMessage.TYPE_INJECT_TEXT:
                msg = parseInjectText(incommingMessage);
                break;
            case ControlMessage.TYPE_INJECT_TOUCH_EVENT:
                msg = parseInjectTouchEvent(incommingMessage);
                break;
            case ControlMessage.TYPE_INJECT_SCROLL_EVENT:
                msg = parseInjectScrollEvent(incommingMessage);
                break;
            case ControlMessage.TYPE_BACK_OR_SCREEN_ON:
                msg = parseBackOrScreenOnEvent(incommingMessage);
                break;
            case ControlMessage.TYPE_SET_CLIPBOARD:
                msg = parseSetClipboard(incommingMessage);
                break;
            case ControlMessage.TYPE_SET_SCREEN_POWER_MODE:
                msg = parseSetScreenPowerMode(incommingMessage);
                break;
            case ControlMessage.TYPE_EXPAND_NOTIFICATION_PANEL:
            case ControlMessage.TYPE_EXPAND_SETTINGS_PANEL:
            case ControlMessage.TYPE_COLLAPSE_PANELS:
            case ControlMessage.TYPE_GET_CLIPBOARD:
            case ControlMessage.TYPE_ROTATE_DEVICE:
                msg = ControlMessage.createEmpty(type);
                break;
            default:
                Ln.w("Unknown event type: " + type);
                msg = null;
                break;
        }
        return msg;
    }


    private ControlMessage parsePushFile(ByteBuffer buffer) {
        int re = buffer.remaining();
        byte[] bytes = new byte[re];
        if (re > 0) {
            buffer.get(bytes, 0, re);
        }
        return ControlMessage.createFilePush(bytes);
    }

    private ControlMessage parseInjectKeycode(Map<String, String > msg) {
        int action = Integer.parseInt(msg.get("action"));
        int keycode = Integer.parseInt(msg.get("keycode"));
        int repeat = Integer.parseInt(msg.get("repeat"));
        int metaState = Integer.parseInt(msg.get("metaState"));
        return ControlMessage.createInjectKeycode(action, keycode, repeat, metaState);
    }

    private ControlMessage parseInjectText(Map<String, String > msg) {
        return ControlMessage.createInjectText(msg.get("text"));
    }

    private ControlMessage parseInjectTouchEvent(Map<String, String > msg) {

        int action = Integer.parseInt(msg.get("action"));
        long pointerId = Long.parseLong(msg.get("pointerId"));
        Position position = readPosition(msg);
        // 16 bits fixed-point
        int pressureInt = Integer.parseInt(msg.get("pressure"));
        // convert it to a float between 0 and 1 (0x1p16f is 2^16 as float)
        float pressure = pressureInt == 0xffff ? 1f : (pressureInt / 0x1p16f);
        int buttons = Integer.parseInt(msg.get("button"));
        return ControlMessage.createInjectTouchEvent(action, pointerId, position, pressure, buttons);
    }

    private ControlMessage parseInjectScrollEvent(Map<String, String > msg) {
        Position position = readPosition(msg);
        int hScroll = Integer.parseInt(msg.get("hScroll"));
        int vScroll = Integer.parseInt(msg.get("vScroll"));
        return ControlMessage.createInjectScrollEvent(position, hScroll, vScroll);
    }

    private ControlMessage parseBackOrScreenOnEvent(Map<String, String > msg) {
        int action = Integer.parseInt(msg.get("action"));
        return ControlMessage.createBackOrScreenOn(action);
    }

    private ControlMessage parseSetClipboard(Map<String, String > msg) {
        boolean paste = Boolean.parseBoolean(msg.get("paste"));
        String text = msg.get("text");
        if (text == null) {
            return null;
        }
        return ControlMessage.createSetClipboard(text, paste);
    }

    private ControlMessage parseSetScreenPowerMode(Map<String, String > msg) {
        int mode = Integer.parseInt(msg.get("mode"));
        return ControlMessage.createSetScreenPowerMode(mode);
    }

    private static Position readPosition(Map<String, String> msg) {
        int x = Integer.parseInt(msg.get("positionX"));
        int y = Integer.parseInt(msg.get("positionY"));
        int screenWidth = Integer.parseInt(msg.get("width"));
        int screenHeight = Integer.parseInt(msg.get("height"));
        return new Position(x, y, screenWidth, screenHeight);
    }

    private static int toUnsigned(short value) {
        return value & 0xffff;
    }

    private static int toUnsigned(byte value) {
        return value & 0xff;
    }
}
