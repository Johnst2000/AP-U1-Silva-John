import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class OrdenTotal {

    record Evento(long lamport, String proceso, String evento, String detalle, String archivo, int linea) {
        String msg() {
            for (String parte : detalle.split(",")) {
                if (parte.startsWith("msg=")) {
                    return parte.substring(4);
                }
            }
            return null;
        }

        String formato() {
            return lamport + ";" + proceso + ";" + evento + ";" + detalle;
        }
    }

    public static void main(String[] args) throws IOException {
        Path carpeta = Path.of(args.length > 0 ? args[0] : "logs");
        Path salida = args.length > 1 ? Path.of(args[1]) : carpeta.resolve("orden_total.log");

        List<Evento> eventos = new ArrayList<>();
        int invalidas = 0;
        List<Path> archivos;
        try (Stream<Path> s = Files.list(carpeta)) {
            archivos = s.filter(p -> p.getFileName().toString().endsWith(".log"))
                    .filter(p -> !p.getFileName().toString().equals("orden_total.log"))
                    .sorted().toList();
        }
        if (archivos.isEmpty()) {
            System.err.println("No hay archivos .log en " + carpeta);
            System.exit(1);
        }
        for (Path archivo : archivos) {
            List<String> lineas = Files.readAllLines(archivo, StandardCharsets.UTF_8);
            for (int i = 0; i < lineas.size(); i++) {
                String[] p = lineas.get(i).split(";", 4);
                try {
                    if (p.length != 4) throw new NumberFormatException();
                    eventos.add(new Evento(Long.parseLong(p[0]), p[1], p[2], p[3],
                            archivo.getFileName().toString(), i + 1));
                } catch (NumberFormatException e) {
                    invalidas++;
                    System.err.println("Línea ignorada (" + archivo.getFileName() + ":" + (i + 1) + "): " + lineas.get(i));
                }
            }
        }

        eventos.sort(Comparator.comparingLong(Evento::lamport).thenComparing(Evento::proceso)
                .thenComparing(Evento::archivo).thenComparingInt(Evento::linea));
        List<String> texto = eventos.stream().map(Evento::formato).toList();
        Files.write(salida, texto, StandardCharsets.UTF_8);

        System.out.println("Archivos leídos: " + archivos.stream().map(p -> p.getFileName().toString()).toList());
        System.out.println("Eventos ordenados: " + eventos.size() + " (líneas inválidas: " + invalidas + ")");
        System.out.println("Orden total escrito en: " + salida);

        Map<Evento, Integer> posicion = new HashMap<>();
        for (int i = 0; i < eventos.size(); i++) {
            posicion.put(eventos.get(i), i + 1);
        }
        Map<String, Evento> envioSolicitud = new HashMap<>();
        Map<String, Evento> recepcionSolicitud = new HashMap<>();
        Map<String, Evento> envioRespuesta = new HashMap<>();
        Map<String, Evento> respuestaRecibida = new HashMap<>();
        for (Evento e : eventos) {
            String msg = e.msg();
            if (msg == null) continue;
            switch (e.evento()) {
                case "ENVIO_SOLICITUD" -> envioSolicitud.put(msg, e);
                case "RECEPCION_SOLICITUD" -> recepcionSolicitud.put(msg, e);
                case "ENVIO_RESPUESTA" -> envioRespuesta.put(msg, e);
                case "RESPUESTA_RECIBIDA" -> respuestaRecibida.put(msg, e);
                default -> { }
            }
        }

        int violaciones = 0;
        int paresSolicitud = 0;
        int paresRespuesta = 0;
        Evento[] ejemploSolicitud = new Evento[2];
        Evento[] ejemploRespuesta = new Evento[2];
        for (Map.Entry<String, Evento> par : recepcionSolicitud.entrySet()) {
            Evento envio = envioSolicitud.get(par.getKey());
            if (envio == null) continue;
            paresSolicitud++;
            if (!(envio.lamport() < par.getValue().lamport() && posicion.get(envio) < posicion.get(par.getValue()))) {
                violaciones++;
                System.out.println("VIOLACIÓN solicitud " + par.getKey());
            } else if (ejemploSolicitud[0] == null || (ejemploSolicitud[1] == null
                    && !envio.proceso().equals(ejemploSolicitud[0].proceso()))) {
                ejemploSolicitud[ejemploSolicitud[0] == null ? 0 : 1] = envio;
            }
        }
        for (Map.Entry<String, Evento> par : respuestaRecibida.entrySet()) {
            Evento envio = envioRespuesta.get(par.getKey());
            if (envio == null) continue;
            paresRespuesta++;
            if (!(envio.lamport() < par.getValue().lamport() && posicion.get(envio) < posicion.get(par.getValue()))) {
                violaciones++;
                System.out.println("VIOLACIÓN respuesta " + par.getKey());
            } else if (ejemploRespuesta[0] == null) {
                ejemploRespuesta[0] = envio;
            }
        }
        int sinRecepcion = 0;
        for (String msg : envioSolicitud.keySet()) {
            if (!recepcionSolicitud.containsKey(msg)) sinRecepcion++;
        }

        System.out.println();
        System.out.println("Verificación de la relación causal (envío -> recepción):");
        System.out.println("  Solicitudes puerta -> coordinador comprobadas: " + paresSolicitud);
        System.out.println("  Respuestas coordinador -> puerta comprobadas:  " + paresRespuesta);
        System.out.println("  Solicitudes enviadas que nunca llegaron al coordinador: " + sinRecepcion);
        System.out.println("  Violaciones del orden causal: " + violaciones);
        System.out.println();
        System.out.println("Pares causalmente relacionados (ejemplos):");
        int n = 0;
        for (Evento envio : ejemploSolicitud) {
            if (envio != null) imprimirPar(++n, envio, recepcionSolicitud.get(envio.msg()), posicion);
        }
        for (Evento envio : ejemploRespuesta) {
            if (envio != null) imprimirPar(++n, envio, respuestaRecibida.get(envio.msg()), posicion);
        }
        System.exit(violaciones == 0 && paresSolicitud > 0 ? 0 : 2);
    }

    private static void imprimirPar(int numero, Evento envio, Evento recepcion, Map<Evento, Integer> posicion) {
        System.out.println("  Par " + numero + " (msg " + envio.msg() + "):");
        System.out.println("    envío     L=" + envio.lamport() + " " + envio.proceso() + " " + envio.evento()
                + "  -> posición " + posicion.get(envio) + " en el orden total");
        System.out.println("    recepción L=" + recepcion.lamport() + " " + recepcion.proceso() + " " + recepcion.evento()
                + "  -> posición " + posicion.get(recepcion) + " en el orden total");
        System.out.println("    " + envio.lamport() + " < " + recepcion.lamport() + " y posición "
                + posicion.get(envio) + " < " + posicion.get(recepcion) + ": el orden total respeta la causalidad");
    }
}
