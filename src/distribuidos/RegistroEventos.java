package distribuidos;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

public final class RegistroEventos implements AutoCloseable {

    private final String idProceso;
    private final BufferedWriter salida;
    private final Set<String> eco;
    private final Path archivo;

    public RegistroEventos(String idProceso, Path carpeta, String nombreArchivo, Set<String> eco) throws IOException {
        this.idProceso = idProceso;
        this.eco = eco;
        Files.createDirectories(carpeta);
        this.archivo = carpeta.resolve(nombreArchivo);
        this.salida = Files.newBufferedWriter(archivo, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized void registrar(long lamport, String evento, String detalle) {
        String limpio = detalle == null ? "" : detalle.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
        try {
            salida.write(lamport + ";" + idProceso + ";" + evento + ";" + limpio);
            salida.newLine();
            salida.flush();
        } catch (IOException e) {
            System.err.println("[" + idProceso + "] No se pudo escribir en " + archivo + ": " + e.getMessage());
        }
        if (eco.contains(evento)) {
            System.out.println("[L=" + lamport + "] " + idProceso + " " + evento + (limpio.isEmpty() ? "" : "  " + limpio));
        }
    }

    public Path archivo() {
        return archivo;
    }

    @Override
    public synchronized void close() {
        try {
            salida.close();
        } catch (IOException e) {
            System.err.println("[" + idProceso + "] Error al cerrar " + archivo + ": " + e.getMessage());
        }
    }
}
