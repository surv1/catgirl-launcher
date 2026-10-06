package net.catgirl.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Discord Rich Presence from inside the game, so the status stays while Minecraft runs
 * even after the launcher is closed. Uses Discord's local IPC: a named pipe on Windows,
 * a Unix socket on macOS/Linux. Frames are [op int32 LE][length int32 LE][JSON].
 * Runs on its own daemon thread; never blocks or crashes the game.
 */
final class DiscordRpc implements Runnable {
    private final String clientId;
    private volatile String wantedJson = null; // activity we want shown (JSON), set from the game thread
    private String sentJson = null;
    private Transport transport = null;

    DiscordRpc(String clientId) { this.clientId = clientId; }

    void start() {
        Thread t = new Thread(this, "Catgirl-Discord");
        t.setDaemon(true);
        t.start();
    }

    /** Called from the game thread whenever what the player is doing changes. */
    void setActivity(JsonObject activity) {
        wantedJson = activity == null ? null : activity.toString();
    }

    @Override
    public void run() {
        while (true) {
            try {
                if (transport == null) {
                    transport = connect();
                    if (transport == null) { sleep(15000); continue; }
                    handshake();
                    sentJson = null;
                }
                String want = wantedJson;
                if (want != null && !want.equals(sentJson)) {
                    sendActivity(want);
                    sentJson = want;
                }
                sleep(2000);
            } catch (Exception e) {
                close();
                sleep(15000);
            }
        }
    }

    private void handshake() throws IOException {
        JsonObject hello = new JsonObject();
        hello.addProperty("v", 1);
        hello.addProperty("client_id", clientId);
        transport.write(frame(0, hello.toString()));
        Frame f = transport.readFrame(); // expect READY
        if (f.op != 1) throw new IOException("Discord refused the connection: " + f.json);
    }

    private void sendActivity(String activityJson) throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        args.add("activity", JsonParser.parseString(activityJson));
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "SET_ACTIVITY");
        cmd.add("args", args);
        cmd.addProperty("nonce", UUID.randomUUID().toString());
        transport.write(frame(1, cmd.toString()));
        Frame reply = transport.readFrame(); // read the answer so the pipe never fills up
        if (reply.op == 2) throw new IOException("Discord closed the connection");
    }

    private void close() {
        if (transport != null) { try { transport.close(); } catch (IOException ignored) {} }
        transport = null;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    static byte[] frame(int op, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(op).putInt(body.length).put(body);
        return b.array();
    }

    record Frame(int op, String json) {}

    // ------------------------------------------------------------------ transports

    interface Transport extends Closeable {
        void write(byte[] data) throws IOException;
        void readFully(byte[] into) throws IOException;

        default Frame readFrame() throws IOException {
            byte[] head = new byte[8];
            readFully(head);
            ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            int op = h.getInt();
            int len = h.getInt();
            if (len < 0 || len > 1 << 20) throw new IOException("Bad frame length " + len);
            byte[] body = new byte[len];
            readFully(body);
            return new Frame(op, new String(body, StandardCharsets.UTF_8));
        }
    }

    private static Transport connect() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (int i = 0; i < 10; i++) {
            if (windows) {
                try {
                    RandomAccessFile pipe = new RandomAccessFile("\\\\?\\pipe\\discord-ipc-" + i, "rw");
                    return new Transport() {
                        public void write(byte[] d) throws IOException { pipe.write(d); }
                        public void readFully(byte[] into) throws IOException { pipe.readFully(into); }
                        public void close() throws IOException { pipe.close(); }
                    };
                } catch (IOException ignored) {}
            } else {
                for (String dir : unixDirs()) {
                    File f = new File(dir, "discord-ipc-" + i);
                    if (!f.exists()) continue;
                    try {
                        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
                        ch.connect(UnixDomainSocketAddress.of(f.toPath()));
                        return new Transport() {
                            public void write(byte[] d) throws IOException {
                                ByteBuffer b = ByteBuffer.wrap(d);
                                while (b.hasRemaining()) ch.write(b);
                            }
                            public void readFully(byte[] into) throws IOException {
                                ByteBuffer b = ByteBuffer.wrap(into);
                                while (b.hasRemaining()) if (ch.read(b) < 0) throw new IOException("Discord disconnected");
                            }
                            public void close() throws IOException { ch.close(); }
                        };
                    } catch (IOException ignored) {}
                }
            }
        }
        return null;
    }

    private static List<String> unixDirs() {
        List<String> bases = new ArrayList<>();
        for (String env : new String[] {"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
            String v = System.getenv(env);
            if (v != null && !v.isEmpty()) bases.add(v);
        }
        bases.add("/tmp");
        List<String> dirs = new ArrayList<>();
        for (String b : bases) {
            dirs.add(b);
            dirs.add(b + "/app/com.discordapp.Discord"); // Flatpak
            dirs.add(b + "/snap.discord");                // Snap
        }
        return dirs;
    }

    // ------------------------------------------------------------------ activity

    static JsonObject activity(String details, String state, long startedMs, String downloadUrl) {
        JsonObject a = new JsonObject();
        a.addProperty("details", details);
        a.addProperty("state", state);
        JsonObject ts = new JsonObject();
        ts.addProperty("start", startedMs / 1000);
        a.add("timestamps", ts);
        JsonObject assets = new JsonObject();
        assets.addProperty("large_image", "logo");
        assets.addProperty("large_text", "Catgirl Launcher");
        a.add("assets", assets);
        if (downloadUrl != null && downloadUrl.startsWith("https://")) {
            JsonObject button = new JsonObject();
            button.addProperty("label", "Get Catgirl Launcher");
            button.addProperty("url", downloadUrl);
            JsonArray buttons = new JsonArray();
            buttons.add(button);
            a.add("buttons", buttons);
        }
        return a;
    }
}
