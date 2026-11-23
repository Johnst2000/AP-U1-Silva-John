import distribuidos.Configuracion;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class PruebaCarga {

    record Ronda(String modo, int numero, int solicitadas, int autorizadas, int rechazadas, int contadorFinal,
                 int aforoMaximo) {
        boolean excede() {
            return autorizadas > aforoMaximo;
        }
    }

    public static void main(String[] args) throws Exception {
        Path rutaConfig = Path.of("config.properties");
        String modos = "ambos";
        String solicitudes = null;
        String rondas = null;
        String ventana = null;
        for (String a : args) {
            if (a.startsWith("--config=")) rutaConfig = Path.of(a.substring(9));
            else if (a.startsWith("--modo=")) modos = a.substring(7);
            else if (a.startsWith("--solicitudes=")) solicitudes = a.substring(14);
            else if (a.startsWith("--rondas=")) rondas = a.substring(9);
            else if (a.startsWith("--ventana-us=")) ventana = a.substring(13);
            else {
                System.err.println("Argumento desconocido: " + a);
                System.exit(1);
            }
        }
        Configuracion cfg = Configuracion.cargar(rutaConfig);
        if (solicitudes != null) cfg = cfg.con("carga.solicitudes", solicitudes);
        if (rondas != null) cfg = cfg.con("carga.rondas", rondas);
        if (ventana != null) cfg = cfg.con("inseguro.ventana.us", ventana);

        int aforo = cfg.entero("aforo.maximo", 10);
        int nSolicitudes = cfg.entero("carga.solicitudes", 40);
        int nRondas = cfg.entero("carga.rondas", 5);
        List<String> lista = modos.equals("ambos") ? List.of("normal", "inseguro") : List.of(modos);

        System.out.println("=== PruebaCarga - aforo máximo " + aforo + ", " + nSolicitudes + " solicitudes de entrada por ronda, "
                + nRondas + " rondas, ventana del modo inseguro " + cfg.entero("inseguro.ventana.us", 200) + " us ===");
        System.out.println("Sin salidas: si se piden más entradas que el aforo, solo " + aforo + " deben ser autorizadas.");

        List<Ronda> resultados = new ArrayList<>();
        for (String modo : lista) {
            System.out.println();
            System.out.println("--- Modo " + modo + " ---");
            for (int r = 1; r <= nRondas; r++) {
                Ronda ronda = ejecutarRonda(cfg, rutaConfig, modo, r, nSolicitudes, aforo);
                resultados.add(ronda);
                System.out.printf("Ronda %d: solicitadas=%d autorizadas=%d rechazadas=%d contador final del coordinador=%d %s%n",
                        r, ronda.solicitadas(), ronda.autorizadas(), ronda.rechazadas(), ronda.contadorFinal(),
                        ronda.excede() ? "-> SUPERÓ EL AFORO (" + ronda.autorizadas() + " > " + aforo + ")" : "-> aforo respetado");
            }
        }

        System.out.println();
        System.out.println("=== Comparación ===");
        System.out.printf("%-10s %-8s %-12s %-12s %-12s %-16s %s%n", "Modo", "Rondas", "Solicitadas", "Aut. mín.", "Aut. máx.",
                "Rondas > aforo", "Contador final máx.");
        boolean normalCorrecto = true;
        for (String modo : lista) {
            List<Ronda> rs = resultados.stream().filter(x -> x.modo().equals(modo)).toList();
            int min = rs.stream().mapToInt(Ronda::autorizadas).min().orElse(0);
            int max = rs.stream().mapToInt(Ronda::autorizadas).max().orElse(0);
            long excedidas = rs.stream().filter(Ronda::excede).count();
            int contMax = rs.stream().mapToInt(Ronda::contadorFinal).max().orElse(0);
            System.out.printf("%-10s %-8d %-12d %-12d %-12d %-16s %d%n", modo, rs.size(), nSolicitudes, min, max,
                    excedidas + "/" + rs.size(), contMax);
            if (modo.equals("normal") && rs.stream().anyMatch(x -> x.autorizadas() != aforo || x.contadorFinal() != aforo)) {
                normalCorrecto = false;
            }
        }
        System.out.println();
        System.out.println(normalCorrecto
                ? "Modo normal: en todas las rondas se autorizaron exactamente " + aforo + " entradas."
                : "Modo normal: HUBO RONDAS SIN EXACTAMENTE " + aforo + " ENTRADAS AUTORIZADAS (ver arriba).");
        System.exit(normalCorrecto ? 0 : 2);
    }

    private static Ronda ejecutarRonda(Configuracion base, Path rutaConfig, String modo, int numero,
                                       int nSolicitudes, int aforo) throws Exception {
        Path logs = Path.of(base.texto("logs.dir", "logs"), "carga-" + modo, "ronda-" + numero);
        Files.createDirectories(logs);
        int puerto = base.entero("coordinador.puerto", 5000) + 1;
        Configuracion cfg = base.con("coordinador.puerto", Integer.toString(puerto)).con("logs.dir", logs.toString());

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"), "Coordinador",
                "--config=" + rutaConfig, "--puerto=" + puerto, "--modo=" + modo, "--logs=" + logs,
                "--ventana-us=" + base.entero("inseguro.ventana.us", 200));
        pb.redirectErrorStream(true);
        pb.redirectOutput(logs.resolve("consola-coordinador.txt").toFile());
        Process coordinador = pb.start();
        try {
            esperarPuerto(cfg.texto("coordinador.host", "127.0.0.1"), puerto, coordinador);
            List<Puerta> puertas = new ArrayList<>();
            for (String id : cfg.idsPuertas()) {
                Puerta p = new Puerta(id, cfg, false);
                p.iniciar();
                puertas.add(p);
            }
            Thread.sleep(300);
            CountDownLatch salida = new CountDownLatch(1);
            int autorizadas = 0;
            int rechazadas = 0;
            try (ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Puerta.Resultado>> futuros = new ArrayList<>();
                for (int i = 0; i < nSolicitudes; i++) {
                    Puerta puerta = puertas.get(i % puertas.size());
                    String persona = "persona-" + i;
                    futuros.add(hilos.submit(() -> {
                        salida.await();
                        return puerta.registrarEntrada(persona);
                    }));
                }
                salida.countDown();
                for (Future<Puerta.Resultado> f : futuros) {
                    Puerta.Resultado res;
                    try {
                        res = f.get();
                    } catch (ExecutionException e) {
                        throw new IllegalStateException("Falló una solicitud de la prueba", e.getCause());
                    }
                    if (res.autorizada()) autorizadas++;
                    else rechazadas++;
                }
            }
            Puerta.Resultado estado = puertas.get(0).consultarEstado();
            for (Puerta p : puertas) {
                p.close();
            }
            if (estado.aforoActual() < 0) {
                throw new IllegalStateException("No se pudo consultar el estado final: " + estado.detalle());
            }
            return new Ronda(modo, numero, nSolicitudes, autorizadas, rechazadas, estado.aforoActual(), aforo);
        } finally {
            coordinador.destroy();
            coordinador.waitFor();
        }
    }

    private static void esperarPuerto(String host, int puerto, Process proceso) throws IOException, InterruptedException {
        long limite = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < limite) {
            if (!proceso.isAlive()) {
                throw new IOException("El coordinador terminó antes de aceptar conexiones (código " + proceso.exitValue() + ")");
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, puerto), 300);
                return;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        throw new IOException("El coordinador no abrió el puerto " + puerto + " en 15 segundos");
    }
}
