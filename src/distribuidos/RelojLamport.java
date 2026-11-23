package distribuidos;

public final class RelojLamport {

    private long valor;

    public synchronized long tick() {
        return ++valor;
    }

    public synchronized long actualizar(long recibido) {
        valor = Math.max(valor, recibido) + 1;
        return valor;
    }

    public synchronized long valor() {
        return valor;
    }
}
