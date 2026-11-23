package seguridad;

import distribuidos.Configuracion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

public final class Autenticador {

    private final Map<String, byte[]> tokens = new HashMap<>();

    public Autenticador(Configuracion cfg) {
        for (String id : cfg.idsPuertas()) {
            tokens.put(id, cfg.token(id).getBytes(StandardCharsets.UTF_8));
        }
    }

    public boolean conoce(String idPuerta) {
        return tokens.containsKey(idPuerta);
    }

    public boolean esValido(String idPuerta, String token) {
        byte[] esperado = tokens.get(idPuerta);
        if (esperado == null || token == null) {
            return false;
        }
        return MessageDigest.isEqual(esperado, token.getBytes(StandardCharsets.UTF_8));
    }
}
