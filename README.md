# AforoSeguro

Control de aforo (máximo 10 personas) con 3 puertas (P1, P2, P3) y un coordinador. Java 21, sockets TCP, solo librería estándar.

## Compilar
- Linux/macOS: `./compilar.sh`
- Windows: `compilar.bat`

## Ejecutar (una terminal por proceso, desde esta carpeta)
```
java -cp out Coordinador                  (o: Coordinador --modo=inseguro)
java -cp out Puerta P1
java -cp out Puerta P2
java -cp out Puerta P3
```
Comandos en cada puerta: `entrada [persona]`, `salida [persona]`, `estado`, `salir`.

## Pruebas
```
java -cp out PruebaCarga       prueba de carga: modo normal vs inseguro (E3)
java -cp out OrdenTotal logs   orden total por Lamport -> logs/orden_total.log (E6)
java -cp out Escenarios        ejecuta E0 a E6 y guarda la evidencia en docs/evidencias/ (~1 min)
```

## Cómo funciona
- Mensajes de una línea: `TIPO;ORIGEN;TOKEN;LAMPORT;MSGID;DATO`. Token inválido = rechazado y registrado.
- Modo normal: el contador se revisa y cambia dentro de un `ReentrantLock`. Modo inseguro: sin protección (se pierden actualizaciones).
- Latido cada 2 s; si pasan 6 s sin latido la puerta se marca desconectada.
- Reloj de Lamport por proceso. Logs con formato `lamport;id_proceso;evento;detalle`.
- Configuración en `config.properties`.

## Resultados y límites
Ver `docs/ANALISIS.md` y los archivos reales en `docs/evidencias/` y `logs/`.
