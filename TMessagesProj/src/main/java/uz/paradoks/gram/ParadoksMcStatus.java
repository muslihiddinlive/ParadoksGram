package uz.paradoks.gram;

import org.telegram.messenger.AndroidUtilities;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Minecraft Bedrock server status (RakNet "unconnected ping"). Used by the settings page to show whether the server of the
 * community is online, how many players are in and the ping. Nothing is sent unless the user opens the status or taps refresh.
 */
public final class ParadoksMcStatus {

    public interface Callback {
        void onResult(String text);
    }

    private static final byte[] MAGIC = {
            0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78
    };

    private ParadoksMcStatus() {
    }

    /** Pings the configured server on a background thread and returns a one-line status on the UI thread. */
    public static void check(Callback callback) {
        final String host = ParadoksConfig.mcHost();
        final int port = ParadoksConfig.mcPort();
        if (host.isEmpty()) {
            callback.onResult("Server manzili kiritilmagan");
            return;
        }
        new Thread(() -> {
            String text;
            try {
                text = ping(host, port);
            } catch (Throwable t) {
                text = "Offline yoki javob yo'q";
            }
            final String result = text;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
        }, "ParadoksMcStatus").start();
    }

    private static String ping(String host, int port) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(3000);
            InetAddress address = InetAddress.getByName(host);
            ByteBuffer out = ByteBuffer.allocate(33);
            out.put((byte) 0x01);
            out.putLong(System.currentTimeMillis());
            out.put(MAGIC);
            out.putLong(0x50415241L); // client guid
            long start = System.nanoTime();
            socket.send(new DatagramPacket(out.array(), out.position(), address, port));
            byte[] buffer = new byte[2048];
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            socket.receive(reply);
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (reply.getLength() < 35 || buffer[0] != 0x1c) {
                return "Noma'lum javob";
            }
            int length = ((buffer[33] & 0xff) << 8) | (buffer[34] & 0xff);
            if (length <= 0 || 35 + length > reply.getLength()) {
                return "Online, " + ms + " ms";
            }
            String[] parts = new String(buffer, 35, length, StandardCharsets.UTF_8).split(";");
            // MCPE;motd;protocol;version;online;max;...
            if (parts.length >= 6) {
                return "Online: " + parts[4] + "/" + parts[5] + " o'yinchi, " + ms + " ms  •  " + parts[1] + " (" + parts[3] + ")";
            }
            return "Online, " + ms + " ms";
        }
    }
}
