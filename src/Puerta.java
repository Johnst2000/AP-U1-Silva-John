import comunicacion.Canal;
import comunicacion.Mensaje;
import distribuidos.Configuracion;
import distribuidos.RegistroEventos;
import distribuidos.RelojLamport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Puerta implements AutoCloseable {

    public record Resultado(boolean autorizada, String estado, int aforoActual, int aforoMaximo, String detalle) {
    }

    private static final Set<String> ECO_TODO = Set.of("INICIO_PROCESO", "COORDINADOR_CONECTADO",
            "COORDINADOR_NO_DISPONIBLE", "COORDINADOR_RECUPERADO", "LATIDO_RECHAZADO");

    private final String id;
    private final String token;
    private final String host;
    private final int puerto;
    private final int conexionMs;
    private final int lecturaMs;
    private final long latidoMs;
    private final RelojLamport reloj = new RelojLamport();
    private final RegistroEventos registro;
    private final AtomicLong secuencia = new AtomicLong();
    private final String arranque = Long.toString(System.currentTimeMillis() / 1000, 36);
    private ScheduledExecutorService latidos;
    private volatile boolean registrada;

    private Boolean coordinadorOk;

    public Puerta(String id, Configuracion cfg) throws IOException {
        this(id, cfg, true);
    }

    public Puerta(String id, Configuracion cfg, boolean consola) throws IOException {
        if (!cfg.idsPuertas().contains(id)) {
            throw new IllegalArgumentException("Puerta desconocida '" + id + "'. Válidas: " + cfg.idsPuertas());
        }
        this.id = id;
        this.token = cfg.token(id);
        this.host = cfg.texto("coordinador.host", "127.0.0.1");
        this.puerto = cfg.entero("coordinador.puerto", 5000);
        this.conexionMs = cfg.entero("socket.conexion.timeout.ms", 1500);
        this.lecturaMs = cfg.entero("socket.lectura.timeout.ms", 3000);
        this.latidoMs = cfg.entero("heartbeat.intervalo.ms", 2000);
        this.registro = new RegistroEventos(id, Path.of(cfg.texto("logs.dir", "logs")), id + ".log", consola ? ECO_TODO : Set.of());
    }

    public String id() {
        return id;
    }

    public void iniciar() {
        long l = reloj.tick();
        registro.registrar(l, "INICIO_PROCESO", "puerta=" + id + ",coordinador=" + host + ":" + puerto);
        latidos = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "latidos-" + id);
            t.setDaemon(true);
            return t;
        });
        latidos.scheduleWithFixedDelay(this::latido, 0, latidoMs, TimeUnit.MILLISECONDS);
    }

    public Resultado registrarEntrada(String persona) {
        return solicitar("ENTRADA", persona);
    }

    public Resultado registrarSalida(String persona) {
        return solicitar("SALIDA", persona);
    }

    public Resultado consultarEstado() {
        return solicitar("ESTADO", "-");
    }

    public Resultado solicitar(String tipo, String persona) {
        String msgId = id + "-" + arranque + "-" + secuencia.incrementAndGet();
        long lEnvio = reloj.tick();
        registro.registrar(lEnvio, "ENVIO_SOLICITUD", "msg=" + msgId + ",tipo=" + tipo + ",persona=" + persona);
        try {
            String cruda = Canal.intercambiar(host, puerto,
                    new Mensaje(tipo, id, token, lEnvio, msgId, persona).serializar(), conexionMs, lecturaMs);
            Mensaje resp = Mensaje.parsear(cruda);
            long lRecibido = reloj.actualizar(resp.lamport());
            String[] d = resp.dato().split("\\|", 4);
            if (d.length < 4) {
                throw new IllegalArgumentException("respuesta con formato inesperado: " + resp.dato());
            }
            int aforo = Integer.parseInt(d[1]);
            int maximo = Integer.parseInt(d[2]);
            registro.registrar(lRecibido, "RESPUESTA_RECIBIDA", "msg=" + msgId + ",resultado=" + d[0]
                    + ",aforo=" + aforo + "/" + maximo);
            cambiarEstadoCoordinador(true, "");
            return new Resultado(d[0].equals("AUTORIZADA") || d[0].equals("ESTADO"), d[0], aforo, maximo, d[3]);
        } catch (IOException | IllegalArgumentException e) {
            long lFallo = reloj.tick();
            registro.registrar(lFallo, "SOLICITUD_FALLIDA", "msg=" + msgId + ",error=" + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            cambiarEstadoCoordinador(false, e.getClass().getSimpleName() + ": " + e.getMessage());
            return new Resultado(false, "COORDINADOR_NO_DISPONIBLE", -1, -1, String.valueOf(e.getMessage()));
        }
    }

    private void latido() {
        String tipo = registrada ? "HEARTBEAT" : "REGISTRO";
        try {
            long l = reloj.tick();
            String cruda = Canal.intercambiar(host, puerto,
                    new Mensaje(tipo, id, token, l, id + "-" + arranque + "-hb", "").serializar(), conexionMs, lecturaMs);
            Mensaje resp = Mensaje.parsear(cruda);
            reloj.actualizar(resp.lamport());
            if (resp.dato().startsWith("ACK")) {
                registrada = true;
                cambiarEstadoCoordinador(true, "");
            } else {
                long lr = reloj.tick();
                registro.registrar(lr, "LATIDO_RECHAZADO", "respuesta=" + resp.dato());
            }
        } catch (IOException | IllegalArgumentException e) {
            registrada = false;
            cambiarEstadoCoordinador(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private synchronized void cambiarEstadoCoordinador(boolean ok, String detalle) {
        if (coordinadorOk != null && coordinadorOk == ok) {
            return;
        }
        String evento = ok ? (coordinadorOk == null ? "COORDINADOR_CONECTADO" : "COORDINADOR_RECUPERADO")
                : "COORDINADOR_NO_DISPONIBLE";
        coordinadorOk = ok;
        long l = reloj.tick();
        registro.registrar(l, evento, ok ? "coordinador=" + host + ":" + puerto : detalle);
    }

    @Override
    public void close() {
        if (latidos != null) {
            latidos.shutdownNow();
        }
        registro.close();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Uso: Puerta <P1|P2|P3> [--config=ruta] [--logs=carpeta]");
            System.exit(1);
        }
        String id = args[0];
        Path rutaConfig = Path.of("config.properties");
        String logs = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--config=")) {
                rutaConfig = Path.of(args[i].substring(9));
            } else if (args[i].startsWith("--logs=")) {
                logs = args[i].substring(7);
            } else {
                System.err.println("Argumento desconocido: " + args[i]);
                System.exit(1);
            }
        }
        Configuracion cfg;
        try {
            cfg = Configuracion.cargar(rutaConfig);
            if (logs != null) cfg = cfg.con("logs.dir", logs);
        } catch (IOException e) {
            System.err.println("No se pudo leer " + rutaConfig + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        Puerta puerta = new Puerta(id, cfg);
        Runtime.getRuntime().addShutdownHook(new Thread(puerta::close));
        puerta.iniciar();
        System.out.println("=== AforoSeguro - Puerta " + id + " ===");
        System.out.println("Comandos: entrada [persona] | salida [persona] | estado | ayuda | salir");

        BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        int anonimas = 0;
        String linea;
        while ((linea = teclado.readLine()) != null) {
            String[] p = linea.trim().split("\\s+", 2);
            String orden = p[0].toLowerCase();
            String persona = p.length > 1 ? p[1] : "persona-" + (++anonimas);
            switch (orden) {
                case "" -> { }
                case "entrada" -> mostrar(id, "ENTRADA", persona, puerta.registrarEntrada(persona));
                case "salida" -> mostrar(id, "SALIDA", persona, puerta.registrarSalida(persona));
                case "estado" -> {
                    Resultado r = puerta.consultarEstado();
                    if (r.aforoActual() < 0) {
                        System.out.println(id + " -> ESTADO: sin respuesta del coordinador (" + r.detalle() + ")");
                    } else {
                        System.out.println(id + " -> ESTADO: aforo " + r.aforoActual() + "/" + r.aforoMaximo() + " ("
                                + r.detalle() + ")");
                    }
                }
                case "ayuda" -> System.out.println("Comandos: entrada [persona] | salida [persona] | estado | ayuda | salir");
                case "salir" -> {
                    puerta.close();
                    return;
                }
                default -> System.out.println("Comando desconocido: " + orden + " (escriba 'ayuda')");
            }
        }

        new CountDownLatch(1).await();
    }

    private static void mostrar(String id, String tipo, String persona, Resultado r) {
        if (r.aforoActual() < 0) {
            System.out.println(id + " -> " + tipo + " " + persona + ": RECHAZADA, sin respuesta del coordinador ("
                    + r.estado() + ": " + r.detalle() + ")");
        } else {
            System.out.println(id + " -> " + tipo + " " + persona + ": " + r.estado() + " (aforo " + r.aforoActual()
                    + "/" + r.aforoMaximo() + (r.autorizada() ? "" : ", motivo " + r.detalle()) + ")");
        }
    }
}
