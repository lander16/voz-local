# Personal accuracy-corpus recording script

Tracking: [Performance P02, GitHub issue #2](https://github.com/lander16/voz-local/issues/2)

This script creates a private, repeatable corpus for comparing Whisper Small q5_1
and q8_0 on the Pixel 8 Pro. It is optimized for the owner's Mexican Spanish
dictation patterns. It is not evidence of accuracy for other speakers or accents.

## Before recording

- Keep the source recordings and transcripts private. Do not commit them to Git or
  attach them to GitHub issues.
- Record one file per ID and use the ID as the filename, for example
  `es_short_01.m4a`. Do not read the ID or the italicized direction aloud.
- Read the bold text exactly as written, including deliberate repetitions and
  code-switching. Punctuation is a reference-text convention; do not dictate words
  such as “comma” or “period.”
- Speak naturally rather than performing for the recognizer. Redo a clip if the
  spoken words differ from the reference, but keep natural hesitations where the
  script explicitly requests them.
- Use the same phone and microphone position for the clean set. Suggested baseline:
  quiet room, phone 25–35 cm away, portrait orientation, normal speaking volume.
- Disable Battery Saver and avoid charging during the eventual timed comparison.
  Recording itself is not timed, so prioritize consistent, clean source material.
- Preserve the original files. A later preparation step will decode each clip to
  signed 16-bit, 16 kHz, mono PCM and calculate its SHA-256.

The approximate duration labels are targets, not measurements. The manifest must use
the decoded PCM duration after recording.

## Spanish: short clips

### es_short_01 — immediate onset — 1–3 seconds

*Start speaking immediately after tapping Record; do not leave leading silence.*

**Hola, buenos días.**

### es_short_02 — quiet ending — 1–3 seconds

*Say the final two words more quietly, without whispering.*

**Perfecto, muchas gracias.**

### es_short_03 — accents — 2–4 seconds

**El camión llegó después.**

### es_short_04 — numbers — 2–4 seconds

**Nos vemos a las tres quince.**

### es_short_05 — proper name — 2–4 seconds

**Escríbele a Ximena Gutiérrez.**

### es_short_06 — command-like dictation — 2–4 seconds

**Agrega leche, café y jabón.**

### es_short_07 — deliberate repetition — 2–4 seconds

*The repeated word is intentional and must remain in the reference.*

**No, no lo cierres todavía.**

### es_short_08 — code-switching — 2–5 seconds

**Mándame el link por WhatsApp.**

## Spanish: medium clips

### es_medium_01 — conversational — 5–10 seconds

**Oye, ¿dejaste las llaves del coche sobre la mesa del comedor?**

### es_medium_02 — date and time — 6–12 seconds

**La cita quedó para el miércoles dieciséis de septiembre a las nueve treinta de la mañana.**

### es_medium_03 — digits and identifier — 6–12 seconds

**El número de seguimiento es ocho cuatro siete dos, guion, B doce, y termina en noventa y cinco.**

### es_medium_04 — Mexican place names — 6–12 seconds

**María José viajará de Ciudad de México a San Cristóbal de las Casas el próximo viernes.**

### es_medium_05 — technical vocabulary — 7–14 seconds

**Actualiza la aplicación, reinicia el servicio de accesibilidad y verifica que el modelo siga disponible sin conexión.**

### es_medium_06 — punctuation-shaped phrasing — 6–12 seconds

**Primero revisa el documento; después corrige los errores y, finalmente, envía la versión definitiva.**

### es_medium_07 — code-switching — 6–12 seconds

**El build de producción pasó los tests, pero todavía falta revisar el pull request y hacer merge.**

### es_medium_08 — quiet ending — 7–14 seconds

*Use normal volume initially and gradually soften the final clause.*

**Ya revisé todo el informe financiero y, por ahora, no encontré ninguna diferencia importante.**

### es_medium_09 — immediate onset — 5–10 seconds

*Start on the first syllable as soon as recording begins.*

**Necesito cambiar la reservación de mañana para cuatro personas a las siete de la noche.**

### es_medium_10 — natural filler and self-correction — 7–14 seconds

*Say both the filler and correction exactly; they test raw versus cleaned output.*

**Eh, compra dos, perdón, tres botellas de agua y una bolsa de hielo.**

## Spanish: long clips

### es_long_01 — meeting summary — 25–35 seconds

**En la reunión de esta mañana revisamos el avance de la aplicación, identificamos dos errores que afectan la experiencia de dictado y acordamos probar una nueva versión en el teléfono. También decidimos medir el tiempo de respuesta con el mismo audio, mantener apagado el ahorro de batería y documentar cualquier cambio de temperatura antes de comparar los resultados.**

### es_long_02 — names, locations, and schedule — 25–35 seconds

**Alejandro Gómez confirmó que recogerá a María Fernanda Ruiz en la terminal dos del Aeropuerto Internacional Benito Juárez. Su vuelo llega el martes veintidós de septiembre a las dieciocho cuarenta, pero pidió esperar quince minutos antes de llamarle porque primero necesita recoger su equipaje y pasar por la puerta número siete.**

### es_long_03 — financial numbers — 25–35 seconds

**El reporte del tercer trimestre indica ingresos por doce millones ochocientos cincuenta mil pesos, un aumento del catorce punto dos por ciento frente al periodo anterior. Los gastos operativos bajaron de tres millones cuatrocientos mil a dos millones novecientos setenta y cinco mil pesos, mientras que el plazo promedio de pago pasó de cuarenta y ocho a treinta y seis días.**

### es_long_04 — technical dictation and English terms — 25–35 seconds

**Antes de publicar el release, crea una copia del archivo de configuración, ejecuta los unit tests y confirma que el checksum del modelo coincida. Si el benchmark muestra una regresión en el percentil noventa y cinco, no cambies el default: guarda los logs, anota el estado térmico del dispositivo y abre un issue con los pasos exactos para reproducir el problema.**

### es_long_05 — deliberate repetitions and correction — 25–35 seconds

*Keep every repeated or corrected phrase.*

**Quiero que agregues la reunión del jueves, del jueves por la tarde, al calendario. No la programes a las cuatro; mejor ponla a las cuatro y media. Invita a Laura, Laura Méndez, y a Roberto Salas. En la descripción escribe que revisaremos el presupuesto, el calendario de entregas y los pendientes pendientes del equipo de diseño.**

### es_long_06 — quiet ending — 25–35 seconds

*Gradually lower your voice during the final sentence, while remaining intelligible.*

**Después de revisar las fotografías del viaje, seleccioné las mejores imágenes, corregí la fecha y organicé cada carpeta por ciudad. Todavía necesito identificar a algunas personas y escribir una breve descripción de cada lugar. Cuando termine, compartiré una copia con la familia y guardaré el archivo original en el disco externo para revisarlo con calma el próximo fin de semana.**

### es_long_07 — controlled household noise — 25–35 seconds

*Record with a fan or steady air-conditioner audible in the background. Avoid music
and intelligible speech from other people.*

**Para preparar la comida del domingo necesitamos comprar jitomate, cebolla, cilantro, tortillas y un kilo de frijoles. Revisa primero lo que queda en la despensa y anota solamente lo que haga falta. Después llama a Daniel para confirmar cuántas personas vienen, si alguien tiene alguna alergia y a qué hora debemos empezar a calentar la comida.**

### es_long_08 — pause boundaries — 25–35 seconds

*Pause naturally for about one second at each em dash; do not say “guion.”*

**El plan tiene tres etapas — primero, recopilar mediciones confiables — segundo, comparar la precisión de ambos modelos — y tercero, repetir la prueba otro día. Una sola ejecución puede ser engañosa porque la temperatura, las tareas en segundo plano y el orden de las pruebas cambian el resultado. Por eso debemos conservar los datos originales y explicar cualquier valor inesperado.**

## Spanish: extended clips

### es_extended_01 — sustained technical explanation — 60–90 seconds

**La transcripción local tiene una ventaja fundamental: el audio puede permanecer en el dispositivo durante todo el proceso. Sin embargo, afirmar que un modelo es más rápido o más preciso requiere bastante cuidado. Primero debemos usar exactamente las mismas grabaciones, la misma configuración y la misma versión de la aplicación. Después hay que alternar el orden de los modelos, porque el segundo intento podría ejecutarse con archivos ya almacenados en memoria o con el procesador más caliente. También conviene separar las pruebas en frío de las pruebas repetidas y registrar el ahorro de batería, el estado de carga y la temperatura. La velocidad por sí sola no basta. Tenemos que revisar nombres, cantidades, negaciones, palabras omitidas y repeticiones que podrían cambiar el significado. Finalmente, los resultados deben incluir la mediana, los casos lentos y cualquier fallo, no solamente la ejecución más favorable. Con ese procedimiento podremos decidir si el modelo nuevo realmente mejora la experiencia diaria o si la diferencia observada fue producto de una prueba incompleta.**

### es_extended_02 — narrative — 60–90 seconds

**El sábado salimos temprano de la Ciudad de México con la intención de llegar a Puebla antes del mediodía. El cielo estaba despejado, pero cerca de Río Frío encontramos neblina y el tránsito avanzó con mucha lentitud. Aprovechamos la pausa para revisar la ruta y buscar una cafetería tranquila. Al llegar al centro caminamos por varias calles, visitamos una librería pequeña y preguntamos por un restaurante que preparaba mole poblano. La mesera nos explicó que la receta llevaba varias horas y que cada familia utilizaba una combinación distinta de chiles y especias. Después de comer fuimos al museo, aunque tuvimos que esperar porque nuestra reservación aparecía registrada para el domingo. Mostramos el correo de confirmación, verificaron el número de folio y finalmente nos permitieron entrar. Antes de regresar compramos pan, tomamos algunas fotografías y anotamos los lugares que queríamos visitar con más tiempo en el siguiente viaje.**

### es_extended_03 — quantities and dates — 60–90 seconds

**Durante el periodo comprendido entre enero y junio de dos mil veintiséis, el equipo atendió mil doscientas cuarenta y ocho solicitudes. Setecientas treinta y dos se resolvieron durante el primer contacto, trescientas once necesitaron una revisión adicional y las restantes doscientas cinco fueron transferidas a un especialista. El tiempo medio de respuesta bajó de cuarenta y dos minutos a veintisiete minutos, aunque el percentil noventa y cinco se mantuvo cerca de una hora con dieciocho minutos. Para el siguiente semestre se propone contratar a seis personas, ampliar el horario de atención hasta las veintiuna horas e invertir dos millones y medio de pesos en herramientas internas. La primera evaluación está programada para el quince de octubre y la decisión final deberá tomarse antes del treinta de noviembre. Todas las cifras tendrán que verificarse contra el informe firmado, porque una diferencia de apenas un dígito podría modificar las conclusiones y el presupuesto aprobado.**

### es_extended_04 — conversational code-switching — 60–90 seconds

**Ayer estuve revisando el performance de la aplicación y encontré algo raro. El modelo q ocho parecía más rápido en algunas pruebas, pero el resultado cambiaba cuando activaba Battery Saver o cuando el teléfono llevaba varios minutos trabajando. Primero pensé que era un problema del thread count, aunque después vi que también influían el orden de ejecución y el estado del cache. Mi propuesta es guardar un baseline con q cinco, correr exactamente el mismo audio con q ocho y repetir el bloque alternando el orden. Si aparece un outlier, no debemos borrarlo sin explicación; hay que revisar los logs y confirmar si ocurrió una cancelación, un thermal throttle o alguna tarea en background. Cuando tengamos suficientes repeticiones podemos comparar median, p ninety-five, word error rate y character error rate. Si la mejora de velocidad es consistente y la transcripción conserva nombres, números y negaciones, entonces sí tendría sentido cambiar la recomendación dentro de la app.**

## English regression clips

These clips detect an obvious non-Spanish regression; they are not intended to be a
representative English benchmark.

### en_short_01 — immediate onset — 2–4 seconds

*Start immediately.*

**Please send the updated file.**

### en_short_02 — proper name and time — 3–6 seconds

**Call Alexander Smith at nine thirty.**

### en_medium_01 — numbers — 6–12 seconds

**The total for order four thousand two hundred fifty is twelve dollars and ninety-nine cents.**

### en_medium_02 — quiet ending — 6–12 seconds

*Lower your volume over the final clause.*

**I reviewed the document and left one final comment near the bottom of the last page.**

### en_medium_03 — technical vocabulary — 7–14 seconds

**Run the production benchmark twice, verify the model checksum, and attach the anonymized results to the issue.**

### en_long_01 — sustained regression — 25–35 seconds

**Before we promote the new model, we need to compare it with the current version under identical conditions. Each run should use the same audio, decoding settings, thread count, and application build. We should record failures and unusually slow results instead of discarding them, then repeat the winning configuration on another day to confirm that the improvement is reproducible.**

## Non-speech controls

These files intentionally have an empty reference transcript. The corpus tooling must
support empty references before they are enabled in an executable manifest.

### control_silence_01 — room silence — 10 seconds

Record ten seconds in the same quiet room without speaking or deliberately making
noise.

### control_noise_01 — steady household noise — 10 seconds

Record ten seconds of the same fan or air-conditioner used for `es_long_07`, without
speech.

### control_pause_01 — delayed onset — 8–12 seconds

Start recording, remain silent for three seconds, then say:

**Esta frase comienza después de una pausa.**

## Recording-condition ledger

Keep this ledger locally with the recordings. Do not include your name or sensitive
location details. One corpus-level consent record may cover all clips when the same
speaker and terms apply.

| Field | Value to record |
|---|---|
| Speaker | Stable pseudonymous ID, such as `speaker_01` |
| Consent evidence | Local consent record ID and date |
| Redistribution | `private-evaluation-only` unless you explicitly choose otherwise |
| Device and microphone | Phone model and built-in/external microphone |
| Original format | Container, codec, sample rate, channels |
| Environment | Quiet room, steady household noise, or delayed-onset control |
| Distance/orientation | Approximate distance and phone orientation |
| Recording date | Local date |
| Notes | Interruptions, deviations, or reasons for a retake |

Do not infer consent to publish from consent to run a private benchmark. If these
recordings remain private, the executable manifest should refer to the local consent
record and must not claim an open-source audio license.
