package distribuidos;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class Configuracion {

    private final Properties propiedades = new Properties();

    private Configuracion() {
    }

    public static Configuracion cargar(Path ruta) throws IOException {
        Configuracion c = new Configuracion();
        try (Reader lector = Files.newBufferedReader(ruta, StandardCharsets.UTF_8)) {
            c.propiedades.load(lector);
        }
        return c;
    }

    public Configuracion con(String clave, String valor) {
        Configuracion c = new Configuracion();
        c.propiedades.putAll(this.propiedades);
        c.propiedades.setProperty(clave, valor);
        return c;
    }

    public String texto(String clave) {
        String v = propiedades.getProperty(clave);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Falta la clave obligatoria '" + clave + "' en config.properties");
        }
        return v.trim();
    }

    public String texto(String clave, String porDefecto) {
        String v = propiedades.getProperty(clave);
        return (v == null || v.isBlank()) ? porDefecto : v.trim();
    }

    public int entero(String clave, int porDefecto) {
        String v = propiedades.getProperty(clave);
        if (v == null || v.isBlank()) {
            return porDefecto;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("La clave '" + clave + "' debe ser un entero, pero vale '" + v + "'");
        }
    }

    public long largo(String clave, long porDefecto) {
        return entero(clave, (int) porDefecto);
    }

    public List<String> idsPuertas() {
        int n = entero("puertas.numero", 3);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            ids.add("P" + i);
        }
        return ids;
    }

    public String token(String idPuerta) {
        return texto("token." + idPuerta);
    }

    public String resumenSinSecretos() {
        StringBuilder sb = new StringBuilder();
        propiedades.stringPropertyNames().stream().sorted().forEach(k -> {
            String v = k.startsWith("token.") ? "(definido, " + propiedades.getProperty(k).length() + " caracteres)"
                    : propiedades.getProperty(k);
            sb.append(k).append('=').append(v).append('\n');
        });
        return sb.toString();
    }
}
