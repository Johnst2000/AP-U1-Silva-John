import comunicacion.Canal;
import comunicacion.Mensaje;
import distribuidos.Configuracion;
import distribuidos.RegistroEventos;
import distribuidos.RelojLamport;
import seguridad.Autenticador;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

public class Coordinador {

    public enum Modo { NORMAL, INSEGURO }

    private record Decision(boolean autorizada, int aforo, String motivo) {
    }

    private static final class InfoPuerta {
        boolean conectada;
        boolean vistaAlgunaVez;
        long ultimoLatidoNs;
    }

    private static final Set<String> ECO = Set.of("INICIO_PROCESO", "DECISION", "AUTENTICACION_FALLIDA",
            "PUERTA_CONECTADA", "PUERTA_REACTIVADA", "PUERTA_DESCONECTADA", "MENSAJE_INVALIDO", "ERROR_INTERNO");

    private final Modo modo;
    private final int aforoMaximo;
    private final int puerto;
    private final String bind;
    private final long timeoutMs;
    private final long ventanaNs;
    private final Autenticador autenticador;
    private final RelojLamport reloj = new RelojLamport();
    private final RegistroEventos registro;
    private final Map<String, InfoPuerta> puertas = new LinkedHashMap<>();

    private final ReentrantLock cerrojo = new ReentrantLock();

    private int contador;

    private final AtomicInteger entradasAutorizadas = new AtomicInteger();
    private final AtomicInteger entradasRechazadas = new AtomicInteger();
    private final AtomicInteger salidasAutorizadas = new AtomicInteger();
    private final AtomicInteger salidasRechazadas = new AtomicInteger();

    private volatile boolean activo = true;
    private volatile ServerSocket servidor;

    public Coordinador(Configuracion cfg, Modo modo) throws IOException {
        this.modo = modo;
        this.aforoMaximo = cfg.entero("aforo.maximo", 10);
        this.puerto = cfg.entero("coordinador.puerto", 5000);
        this.bind = cfg.texto("coordinador.bind", "127.0.0.1");
        this.timeoutMs = cfg.entero("heartbeat.timeout.ms", 6000);
        this.ventanaNs = cfg.entero("inseguro.ventana.us", 200) * 1000L;
        this.autenticador = new Autenticador(cfg);
        for (String id : cfg.idsPuertas()) {
            puertas.put(id, new InfoPuerta());
        }
        this.registro = new RegistroEventos("COORD", Path.of(cfg.texto("logs.dir", "logs")), "coordinador.log", ECO);
    }

    public static void main(String[] args) throws Exception {
        Path rutaConfig = Path.of("config.properties");
        String puerto = null;
        String modoTexto = null;
        String logs = null;
        String ventana = null;
        for (String a : args) {
            if (a.startsWith("--config=")) {
                rutaConfig = Path.of(a.substring(9));
            } else if (a.startsWith("--puerto=")) {
                puerto = a.substring(9);
            } else if (a.startsWith("--modo=")) {
                modoTexto = a.substring(7);
            } else if (a.startsWith("--logs=")) {
                logs = a.substring(7);
            } else if (a.startsWith("--ventana-us=")) {
                ventana = a.substring(13);
            } else {
                System.err.println("Argumento desconocido: " + a);
                System.err.println("Uso: Coordinador [--config=ruta] [--puerto=N] [--modo=normal|inseguro] [--logs=carpeta] [--ventana-us=N]");
                System.exit(1);
            }
        }
        Configuracion cfg;
        try {
            cfg = Configuracion.cargar(rutaConfig);
        } catch (IOException e) {
            System.err.println("No se pudo leer " + rutaConfig + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        if (puerto != null) cfg = cfg.con("coordinador.puerto", puerto);
        if (logs != null) cfg = cfg.con("logs.dir", logs);
        if (ventana != null) cfg = cfg.con("inseguro.ventana.us", ventana);
        String m = modoTexto != null ? modoTexto : cfg.texto("modo", "normal");
        Modo modo;
        try {
            modo = Modo.valueOf(m.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("Modo desconocido '" + m + "'. Use normal o inseguro.");
            System.exit(1);
            return;
        }
        new Coordinador(cfg, modo).ejecutar();
    }

    public void ejecutar() throws IOException {
        long l = reloj.tick();
        registro.registrar(l, "INICIO_PROCESO", "modo=" + modo.name().toLowerCase() + ",aforoMaximo=" + aforoMaximo
                + ",puerto=" + puerto + ",puertas=" + String.join("/", puertas.keySet()));
        System.out.println("=== AforoSeguro - Coordinador - modo " + modo.name().toLowerCase() + " - aforo máximo "
                + aforoMaximo + " - escuchando en " + bind + ":" + puerto + " ===");
        if (modo == Modo.INSEGURO) {
            System.out.println("ATENCIÓN: modo inseguro, el contador no está protegido por exclusión mutua.");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            activo = false;
            try {
                if (servidor != null) servidor.close();
            } catch (IOException ignorada) {

            }
            registro.close();
        }));

        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "monitor-latidos");
            t.setDaemon(true);
            return t;
        });
        monitor.scheduleWithFixedDelay(this::vigilarLatidos, 500, 500, TimeUnit.MILLISECONDS);

        try (ServerSocket ss = new ServerSocket(); ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(bind, puerto), 256);
            servidor = ss;
            while (activo) {
                Socket cliente = ss.accept();
                hilos.submit(() -> atender(cliente));
            }
        } catch (SocketException e) {
            if (activo) {
                throw e;
            }
        } finally {
            monitor.shutdownNow();
        }
    }

    private void atender(Socket socket) {
        String ip = socket.getInetAddress().getHostAddress();
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(10_000);
            String linea;
            while ((linea = Canal.leerLinea(in)) != null) {
                String respuesta;
                try {
                    respuesta = procesar(linea, ip);
                } catch (RuntimeException e) {
                    long l = reloj.tick();
                    registro.registrar(l, "ERROR_INTERNO", "ip=" + ip + ",error=" + e);
                    respuesta = new Mensaje("RESP", "COORD", "-", l, "-", "ERROR|-1|-1|ERROR_INTERNO").serializar();
                }
                out.write(respuesta);
                out.write('\n');
                out.flush();
            }
        } catch (SocketTimeoutException e) {

        } catch (IOException e) {
            long l = reloj.tick();
            registro.registrar(l, "ERROR_CONEXION", "ip=" + ip + ",error=" + e.getMessage());
        }
    }

    private String procesar(String linea, String ip) {
        Mensaje m;
        try {
            m = Mensaje.parsear(linea);
        } catch (IllegalArgumentException e) {
            long l = reloj.tick();
            registro.registrar(l, "MENSAJE_INVALIDO", "ip=" + ip + ",error=" + e.getMessage());
            return respuesta(l, "-", "ERROR", -1, "FORMATO_INVALIDO");
        }

        if (!autenticador.esValido(m.origen(), m.token())) {

            long l = reloj.tick();
            String motivo = autenticador.conoce(m.origen()) ? "token_invalido" : "puerta_desconocida";
            registro.registrar(l, "AUTENTICACION_FALLIDA", "origen=" + m.origen() + ",ip=" + ip + ",tipo=" + m.tipo()
                    + ",motivo=" + motivo);
            return respuesta(l, m.msgId(), "RECHAZADA", -1, "AUTENTICACION_FALLIDA");
        }

        long lRecepcion = reloj.actualizar(m.lamport());
        switch (m.tipo()) {
            case "REGISTRO", "HEARTBEAT" -> {
                marcarActiva(m.origen());
                return respuestaSinRegistro("ACK", "latido_recibido");
            }
            case "ENTRADA", "SALIDA" -> {
                registro.registrar(lRecepcion, "RECEPCION_SOLICITUD", "msg=" + m.msgId() + ",puerta=" + m.origen()
                        + ",tipo=" + m.tipo() + ",persona=" + m.dato());
                marcarActiva(m.origen());
                boolean entrada = m.tipo().equals("ENTRADA");
                Decision d = entrada ? entrar() : salir();
                AtomicInteger contadorEstadistica = entrada
                        ? (d.autorizada() ? entradasAutorizadas : entradasRechazadas)
                        : (d.autorizada() ? salidasAutorizadas : salidasRechazadas);
                contadorEstadistica.incrementAndGet();
                long lDecision = reloj.tick();
                String resultado = d.autorizada() ? "AUTORIZADA" : "RECHAZADA";
                registro.registrar(lDecision, "DECISION", "msg=" + m.msgId() + ",puerta=" + m.origen() + ",tipo="
                        + m.tipo() + ",persona=" + m.dato() + ",resultado=" + resultado + ",motivo=" + d.motivo()
                        + ",aforo=" + d.aforo() + "/" + aforoMaximo);
                return respuestaConDecision(m.msgId(), resultado, d.aforo(), d.motivo());
            }
            case "ESTADO" -> {
                registro.registrar(lRecepcion, "RECEPCION_SOLICITUD", "msg=" + m.msgId() + ",puerta=" + m.origen()
                        + ",tipo=ESTADO,persona=-");
                marcarActiva(m.origen());
                int actual = leerContador();
                String detalle = "entradasAutorizadas=" + entradasAutorizadas.get()
                        + ",entradasRechazadas=" + entradasRechazadas.get()
                        + ",salidasAutorizadas=" + salidasAutorizadas.get()
                        + ",salidasRechazadas=" + salidasRechazadas.get()
                        + ",modo=" + modo.name().toLowerCase() + ",contador=" + actual;
                return respuestaConDecision(m.msgId(), "ESTADO", actual, detalle);
            }
            default -> {
                long l = reloj.tick();
                registro.registrar(l, "MENSAJE_INVALIDO", "origen=" + m.origen() + ",tipo_desconocido=" + m.tipo());
                return respuesta(l, m.msgId(), "ERROR", -1, "TIPO_DESCONOCIDO");
            }
        }
    }

    private String respuestaConDecision(String msgId, String resultado, int aforo, String detalle) {
        long l = reloj.tick();
        registro.registrar(l, "ENVIO_RESPUESTA", "msg=" + msgId + ",resultado=" + resultado);
        return new Mensaje("RESP", "COORD", "-", l, msgId, resultado + "|" + aforo + "|" + aforoMaximo + "|" + detalle)
                .serializar();
    }

    private String respuesta(long lamport, String msgId, String resultado, int aforo, String detalle) {
        return new Mensaje("RESP", "COORD", "-", lamport, msgId, resultado + "|" + aforo + "|" + aforoMaximo + "|" + detalle)
                .serializar();
    }

    private String respuestaSinRegistro(String resultado, String detalle) {
        long l = reloj.tick();
        return new Mensaje("RESP", "COORD", "-", l, "-", resultado + "|-1|" + aforoMaximo + "|" + detalle).serializar();
    }

    private Decision entrar() {
        if (modo == Modo.NORMAL) {
            cerrojo.lock();
            try {
                if (contador < aforoMaximo) {
                    contador++;
                    return new Decision(true, contador, "OK");
                }
                return new Decision(false, contador, "AFORO_COMPLETO");
            } finally {
                cerrojo.unlock();
            }
        }

        int actual = contador;
        if (actual < aforoMaximo) {
            esperarVentana();
            contador = actual + 1;
            return new Decision(true, actual + 1, "OK");
        }
        return new Decision(false, actual, "AFORO_COMPLETO");
    }

    private Decision salir() {
        if (modo == Modo.NORMAL) {
            cerrojo.lock();
            try {
                if (contador > 0) {
                    contador--;
                    return new Decision(true, contador, "OK");
                }
                return new Decision(false, contador, "AFORO_VACIO");
            } finally {
                cerrojo.unlock();
            }
        }
        int actual = contador;
        if (actual > 0) {
            esperarVentana();
            contador = actual - 1;
            return new Decision(true, actual - 1, "OK");
        }
        return new Decision(false, actual, "AFORO_VACIO");
    }

    private void esperarVentana() {
        if (ventanaNs > 0) {
            LockSupport.parkNanos(ventanaNs);
        }
    }

    private int leerContador() {
        if (modo == Modo.NORMAL) {
            cerrojo.lock();
            try {
                return contador;
            } finally {
                cerrojo.unlock();
            }
        }
        return contador;
    }

    private void marcarActiva(String idPuerta) {
        InfoPuerta info = puertas.get(idPuerta);
        if (info == null) {
            return;
        }
        String evento = null;
        synchronized (info) {
            info.ultimoLatidoNs = System.nanoTime();
            if (!info.conectada) {
                info.conectada = true;
                evento = info.vistaAlgunaVez ? "PUERTA_REACTIVADA" : "PUERTA_CONECTADA";
                info.vistaAlgunaVez = true;
            }
        }
        if (evento != null) {
            long l = reloj.tick();
            registro.registrar(l, evento, "puerta=" + idPuerta);
            imprimirResumen();
        }
    }

    private void vigilarLatidos() {
        long ahora = System.nanoTime();
        for (Map.Entry<String, InfoPuerta> e : puertas.entrySet()) {
            InfoPuerta info = e.getValue();
            long sinLatidosMs = -1;
            synchronized (info) {
                if (info.conectada) {
                    long ms = TimeUnit.NANOSECONDS.toMillis(ahora - info.ultimoLatidoNs);
                    if (ms >= timeoutMs) {
                        info.conectada = false;
                        sinLatidosMs = ms;
                    }
                }
            }
            if (sinLatidosMs >= 0) {
                long l = reloj.tick();
                registro.registrar(l, "PUERTA_DESCONECTADA", "puerta=" + e.getKey() + ",sinLatidos_ms=" + sinLatidosMs);
                imprimirResumen();
            }
        }
    }

    private void imprimirResumen() {
        StringBuilder conectadas = new StringBuilder();
        StringBuilder desconectadas = new StringBuilder();
        for (Map.Entry<String, InfoPuerta> e : puertas.entrySet()) {
            boolean viva;
            synchronized (e.getValue()) {
                viva = e.getValue().conectada;
            }
            (viva ? conectadas : desconectadas).append(e.getKey()).append(' ');
        }
        System.out.println("    >> Aforo " + leerContador() + "/" + aforoMaximo + " | Conectadas: "
                + (conectadas.length() == 0 ? "-" : conectadas.toString().trim()) + " | Desconectadas: "
                + (desconectadas.length() == 0 ? "-" : desconectadas.toString().trim()));
    }
}
