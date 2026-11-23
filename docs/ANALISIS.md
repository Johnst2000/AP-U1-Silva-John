# Análisis (E3, E5, E6) - resultados de ejecuciones reales

**E3 carga (40 entradas simultáneas, aforo 10, 5 rondas).** Modo normal: siempre exactamente 10 autorizadas.
Modo inseguro: el aforo se superó en 5 de 5 rondas y el contador final quedó por debajo de las autorizadas
(actualizaciones perdidas). Con pausa 0 µs entre leer y escribir no se observó la carrera, por eso el valor por
defecto es 200 µs (`inseguro.ventana.us`). Detalle: `docs/evidencias/E3-prueba-de-carga.txt` y `E3b-*`.

**E5 caída del coordinador.** Sin coordinador las puertas rechazan todo (no pueden saber el aforo) y siguen
intentando reconectar. No hay elección de coordinador. Al reiniciarlo el contador vuelve a 0 (solo está en memoria),
así que con gente dentro podría superarse el aforo real. Las solicitudes enviadas durante la caída se pierden.

**E6 Lamport.** `OrdenTotal` ordena por (lamport, proceso) y comprobó todos los pares envío→recepción:
0 violaciones. Ejemplo: `P3 ENVIO_SOLICITUD` L=27 antes de `COORD RECEPCION_SOLICITUD` L=35; y
`COORD ENVIO_RESPUESTA` L=36 antes de `P3 RESPUESTA_RECIBIDA` L=37. L(A)<L(B) no implica que A causó B.

**Límites:** un solo coordinador (punto único de fallo), relojes no persistentes, sin reintentos, tokens en texto
plano, sin cifrado, probado solo en localhost.
