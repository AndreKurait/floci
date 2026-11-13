
import java.net.*;
import java.util.Arrays;
public class DnsProbe {
    public static void main(String[] args) throws Exception {
        if (!args[0].equals("serve")) {
            int failures = 0;
            for (int i = 1; i < args.length; i++) {
                try {
                    System.out.println(args[i] + "=" + InetAddress.getByName(args[i]).getHostAddress());
                } catch (UnknownHostException failure) {
                    System.out.println(args[i] + "=UNKNOWN");
                    failures++;
                }
            }
            System.exit(failures == 0 ? 0 : 2);
        }
        try (DatagramSocket socket = new DatagramSocket(53)) {
            while (true) {
                DatagramPacket request = new DatagramPacket(new byte[4096], 4096);
                socket.receive(request);
                byte[] input = request.getData();
                int end = 12;
                while (input[end] != 0) end += 1 + (input[end] & 255);
                int type = ((input[end + 1] & 255) << 8) | (input[end + 2] & 255);
                end += 5;
                byte[] response = Arrays.copyOf(input, end + (type == 1 ? 16 : 0));
                response[2] = (byte) 0x81;
                response[3] = (byte) 0x80;
                response[6] = 0; response[7] = (byte) (type == 1 ? 1 : 0);
                response[8] = 0; response[9] = 0; response[10] = 0; response[11] = 0;
                if (type == 1) {
                    byte[] answer = {(byte) 0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 1, 0, 4,
                                     (byte) 203, 0, 113, 99};
                    System.arraycopy(answer, 0, response, end, 16);
                }
                socket.send(new DatagramPacket(response, response.length,
                                               request.getAddress(), request.getPort()));
            }
        }
    }
}
