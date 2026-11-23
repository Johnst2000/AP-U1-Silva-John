package comunicacion;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class Canal {

    public static final int MAX_LINEA = 4096;

    private Canal() {
    }

    public static String intercambiar(String host, int puerto, String linea, int conexionMs, int lecturaMs)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, puerto), conexionMs);
            socket.setSoTimeout(lecturaMs);
            socket.setTcpNoDelay(true);
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.write(linea);
            out.write('\n');
            out.flush();
            String respuesta = leerLinea(in);
            if (respuesta == null) {
                throw new EOFException("el coordinador cerró la conexión sin responder");
            }
            return respuesta;
        }
    }

    public static String leerLinea(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') {
                    sb.setLength(n - 1);
                }
                return sb.toString();
            }
            if (sb.length() >= MAX_LINEA) {
                throw new IOException("línea demasiado larga (más de " + MAX_LINEA + " caracteres)");
            }
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
