package comunicacion;

public record Mensaje(String tipo, String origen, String token, long lamport, String msgId, String dato) {

    private static final int CAMPOS = 6;

    public String serializar() {
        return String.join(";", limpiar(tipo), limpiar(origen), limpiar(token),
                Long.toString(lamport), limpiar(msgId), limpiar(dato));
    }

    public static Mensaje parsear(String linea) {
        if (linea == null || linea.isBlank()) {
            throw new IllegalArgumentException("mensaje vacío");
        }
        String[] p = linea.split(";", CAMPOS);
        if (p.length != CAMPOS) {
            throw new IllegalArgumentException("se esperaban " + CAMPOS + " campos y llegaron " + p.length);
        }
        long reloj;
        try {
            reloj = Long.parseLong(p[3]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("el reloj lógico no es un número: " + p[3]);
        }
        if (reloj < 0 || p[0].isEmpty() || p[1].isEmpty()) {
            throw new IllegalArgumentException("tipo, origen o reloj inválidos");
        }
        return new Mensaje(p[0], p[1], p[2], reloj, p[4], p[5]);
    }

    private static String limpiar(String s) {
        return s == null ? "" : s.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
    }
}
