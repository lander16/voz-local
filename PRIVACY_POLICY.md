# Privacy Policy for VozLocal

**Effective Date:** September 3, 2026  
**Application Name:** VozLocal  
**Package Name:** `dev.sebastian.vozlocal`  
**Developer:** Sebastian (GitHub: [@lander16](https://github.com/lander16))  
**Repository:** [https://github.com/lander16/voz-local](https://github.com/lander16/voz-local)  
**Contact:** Through GitHub Issues at [https://github.com/lander16/voz-local/issues](https://github.com/lander16/voz-local/issues)

---

## 1. Overview & Core Commitment

**VozLocal is designed from the ground up as a 100% offline, privacy-first local dictation and speech-to-text tool.**

We believe your voice, transcriptions, and personal messages belong solely to you. VozLocal performs all speech recognition, AI model inference, and text polishing locally on your device hardware. 

- **No audio or text is ever sent to the cloud.**
- **No analytics, crash trackers, or telemetry SDKs are embedded.**
- **No advertising networks or marketing trackers are used.**
- **No user profiling or personal data collection occurs.**

---

## 2. Information We Access, Process, and Store

### A. Microphone & Audio Data (`android.permission.RECORD_AUDIO`)
- **How It Is Used:** VozLocal accesses the device microphone exclusively when you explicitly initiate a dictation session (by pressing the in-app microphone button or the floating accessibility overlay button).
- **Processing:** Raw audio is streamed in real-time as uncompressed 16 kHz 16-bit mono PCM samples into local device memory (RAM) where the on-device Whisper engine transcribes it.
- **Retention:** Audio samples exist only temporarily in volatile memory during transcription and are **immediately wiped** as soon as transcription completes or is cancelled. VozLocal **never** saves raw audio recordings, voiceprints, or acoustic files to disk.
- **Third-Party Sharing:** Zero audio data is ever transmitted off your device or shared with third parties.

### B. Accessibility Service (`AccessibilityService` / `android:isAccessibilityTool="true"`)
VozLocal includes an optional Android Accessibility Service designed to provide hands-free voice input across any application on your device.
- **Declared Purpose:** Declared officially as an assistive accessibility tool (`android:isAccessibilityTool="true"`) under Google Play Developer Policy.
- **Capabilities Used:**
  1. **Input Field Detection:** Observes `TYPE_VIEW_FOCUSED` and `TYPE_WINDOW_STATE_CHANGED` accessibility events strictly to detect when an editable text input field (`isEditable == true`) gains focus, displaying the floating microphone overlay button.
  2. **Text Insertion:** Upon your explicit tap on the microphone, the transcribed text is inserted directly into the active field using standard Android accessibility actions (`ACTION_SET_TEXT` / `ACTION_PASTE`).
- **Data Protection & Denylist:**
  - The service **does not** read, log, collect, monitor, or transmit screen contents, keystrokes, passwords, or personal communications.
  - The service explicitly ignores password fields (`isPassword == true`).
  - A built-in security denylist automatically suspends and hides the accessibility overlay when banking apps, digital wallets, password managers, or two-factor authenticators are opened.
- **Retention & Sharing:** No data observed through the Accessibility API is ever logged, stored in persistent memory, or transmitted outside your device.

### C. Local Audio Files (`READ_MEDIA_AUDIO` / File Picker)
- **How It Is Used:** If you choose to import an existing audio file using the "Shared Audio" tab, the file is read and decoded strictly on your device using Android's local `MediaCodec` framework.
- **Retention & Sharing:** The audio content is processed locally and never copied to remote servers or shared externally.

### D. Internet Access (`android.permission.INTERNET`)
- **How It Is Used:** Network access is used solely and strictly when you choose to download or update offline speech models (GGUF/bin files) from official public repositories (such as Hugging Face and GitHub).
- **Integrity:** Model downloads are validated against cryptographic SHA-256 checksums to ensure file integrity and security.
- **Offline Operation:** Once speech models are downloaded, VozLocal functions completely offline. You can disconnect your device from the internet or enable Airplane Mode, and all dictation features remain 100% operational.
- **No Telemetry:** The application makes zero background network requests, analytics pings, or telemetry transmissions.

### E. On-Device Data Storage (History & Custom Dictionary)
- **What Is Stored:**
  - **Transcription History:** Local text logs of your past dictations and their completion duration.
  - **Custom Dictionary:** Words and vocabulary shortcuts you choose to add to personalize speech recognition.
  - **User Preferences:** Local UI settings (selected model, theme, language, and punctuation preferences).
- **Storage Location:** All data is stored locally in an encrypted/sandboxed SQLite database (`AppDatabase` via Android Room) inside your device's private application storage (`/data/data/dev.sebastian.vozlocal/`).
- **User Control & Data Deletion:**
  - You have full control over your stored data. You can delete individual transcription records or clear your entire history at any time from the History tab or Settings sheet.
  - You can export your transcription history anytime using the standard Android Share sheet.
  - Uninstalling VozLocal immediately and permanently purges all local databases, preferences, and downloaded models from your device.

---

## 3. Third-Party Services & SDKs

VozLocal does not integrate or bundle any third-party SDKs that collect data:
- **No Advertising SDKs:** (e.g., AdMob, Unity Ads, Facebook Audience Network).
- **No Analytics SDKs:** (e.g., Google Analytics for Firebase, Flurry, Mixpanel).
- **No Crash Reporting Telemetry:** (e.g., Firebase Crashlytics, Sentry, Bugsnag).
- **No Cloud AI APIs:** (e.g., OpenAI, Google Cloud Speech, AWS Transcribe).

All speech processing is powered by locally vendored, open-source C++ `whisper.cpp` binaries and pure Kotlin text processing algorithms.

---

## 4. Children’s Privacy

VozLocal is not directed to children under the age of 13. Because VozLocal does not collect, transmit, or share any personal information from any user, it does not knowingly collect personal information from children.

---

## 5. Security

We take data security seriously:
- **Android Sandboxing:** All local database files and user preferences reside exclusively within Android's protected application sandbox, inaccessible to other applications without root privileges.
- **Volatile Audio Handling:** Audio streams are held in volatile RAM only during active dictation and never committed to disk.
- **Cryptographic Verification:** Downloadable models are verified with SHA-256 checksums before loading to prevent tampering or corruption.

---

## 6. Google Play Policy Compliance

This Privacy Policy complies with Google Play’s User Data Policy and Developer Distribution Agreement:
- **Prominent Disclosure for Accessibility Services:** Disclosed in Section 2(B) and presented within the application via an in-app Privacy Policy modal dialog.
- **Microphone Data Usage:** Disclosed in Section 2(A).
- **Data Safety Form Accuracy:** In the Google Play Console Data Safety section, VozLocal is accurately declared as collecting **0** data categories and sharing **0** data categories with third parties.

---

## 7. Changes to This Privacy Policy

We may update our Privacy Policy from time to time if new features or requirements are introduced. Any updates will be posted directly to this repository with a revised "Effective Date". We encourage users to review this page periodically.

---

## 8. Contact Us

If you have questions, feedback, or concerns regarding this Privacy Policy or VozLocal's privacy practices, please contact us:
- **GitHub Issues:** [https://github.com/lander16/voz-local/issues](https://github.com/lander16/voz-local/issues)
- **Repository:** [https://github.com/lander16/voz-local](https://github.com/lander16/voz-local)

---

<details>
<summary><b>Versión en Español (Spanish Translation)</b></summary>

# Política de Privacidad de VozLocal

**Fecha de entrada en vigor:** 3 de septiembre de 2026  
**Nombre de la aplicación:** VozLocal  
**Nombre del paquete:** `dev.sebastian.vozlocal`  
**Desarrollador:** Sebastian (GitHub: [@lander16](https://github.com/lander16))  
**Repositorio:** [https://github.com/lander16/voz-local](https://github.com/lander16/voz-local)  
**Contacto:** A través de GitHub Issues en [https://github.com/lander16/voz-local/issues](https://github.com/lander16/voz-local/issues)

---

## 1. Resumen y Compromiso Fundamental

**VozLocal está diseñada desde sus cimientos como una herramienta de dictado y transcripción local 100% sin conexión y con máxima privacidad.**

Creemos que tu voz, tus transcripciones y tus mensajes personales te pertenecen exclusivamente a ti. VozLocal realiza todo el reconocimiento de voz, la inferencia de modelos de IA y el pulido de texto de forma totalmente local en el hardware de tu dispositivo.

- **Ningún audio o texto se envía jamás a la nube.**
- **No se recopila telemetría, analíticas ni registros de fallos remotos.**
- **No contiene redes publicitarias ni herramientas de rastreo.**
- **No se crean perfiles de usuario ni se recopilan datos personales.**

---

## 2. Información que Accedemos, Procesamos y Almacenamos

### A. Datos de Micrófono y Audio (`android.permission.RECORD_AUDIO`)
- **Uso:** VozLocal accede al micrófono del dispositivo exclusivamente cuando inicias de forma voluntaria y explícita una sesión de dictado (pulsando el botón de micrófono en la app o el botón flotante de accesibilidad).
- **Procesamiento:** El audio se procesa en tiempo real como muestras PCM mono de 16 kHz y 16 bits en la memoria RAM del dispositivo, donde el motor Whisper local lo transcribe.
- **Retención:** Las muestras de audio existen únicamente de forma temporal en la memoria volátil durante la transcripción y se **eliminan de inmediato** en cuanto finaliza o se cancela el dictado. VozLocal **nunca** guarda grabaciones de audio en el almacenamiento ni genera huellas de voz.
- **Compartición:** Cero datos de audio se transmiten fuera de tu dispositivo o se comparten con terceros.

### B. Servicio de Accesibilidad (`AccessibilityService` / `android:isAccessibilityTool="true"`)
VozLocal incluye un Servicio de Accesibilidad opcional diseñado para permitir la entrada de voz en cualquier aplicación del dispositivo.
- **Propósito Declarado:** Declarado oficialmente como una herramienta de asistencia de accesibilidad (`android:isAccessibilityTool="true"`) bajo las políticas de Google Play.
- **Funciones Utilizadas:**
  1. **Detección de Campos de Entrada:** Detecta eventos `TYPE_VIEW_FOCUSED` y `TYPE_WINDOW_STATE_CHANGED` estrictamente para reconocer cuándo un campo de texto editable recibe el foco, mostrando el botón flotante de dictado.
  2. **Inserción de Texto:** Al pulsar el botón, el texto transcrito se inserta en el campo activo mediante acciones estándar de Android (`ACTION_SET_TEXT` / `ACTION_PASTE`).
- **Protección y Lista de Exclusión:**
  - El servicio **no** lee, registra, recopila ni transmite el contenido de la pantalla, contraseñas ni mensajes personales.
  - Ignora de forma automática los campos de contraseña (`isPassword == true`).
  - Cuenta con una lista de exclusión que oculta y desactiva el botón flotante en aplicaciones bancarias, gestores de contraseñas y autenticadores.
- **Retención y Compartición:** Ningún dato observado mediante la API de Accesibilidad se guarda en memoria persistente ni se transmite fuera del dispositivo.

### C. Archivos de Audio Locales (`READ_MEDIA_AUDIO` / Selector de Archivos)
- **Uso:** Si decides transcribir un archivo de audio mediante la pestaña "Compartido", el archivo se procesa y decodifica localmente mediante el framework nativo `MediaCodec` de Android.
- **Retención y Compartición:** El contenido nunca se sube a servidores externos ni se comparte.

### D. Acceso a Internet (`android.permission.INTERNET`)
- **Uso:** Se utiliza única y exclusivamente cuando decides descargar o actualizar modelos de voz (archivos GGUF/bin) desde repositorios públicos oficiales (como Hugging Face o GitHub).
- **Integridad:** Las descargas se verifican mediante sumas de comprobación criptográficas SHA-256.
- **Funcionamiento Offline:** Tras descargar el modelo deseado, VozLocal funciona completamente sin conexión (incluso en Modo Avión).
- **Sin Telemetría:** La aplicación no realiza peticiones en segundo plano ni envía métricas de uso.

### E. Almacenamiento en el Dispositivo (Historial y Diccionario)
- **Datos Almacenados:**
  - **Historial de Transcripciones:** Registro local de tus dictados pasados y duración.
  - **Diccionario Personal:** Palabras personalizadas que añadas para mejorar el reconocimiento.
  - **Preferencias:** Configuración de la interfaz, modelo seleccionado, idioma y puntuación.
- **Ubicación:** Todo se guarda en una base de datos SQLite privada en el almacenamiento aislado de la aplicación (`/data/data/dev.sebastian.vozlocal/`).
- **Control del Usuario y Eliminación:**
  - Puedes eliminar registros individuales o vaciar todo el historial en cualquier momento desde la app.
  - Puedes exportar tu historial mediante la hoja para compartir de Android.
  - Desinstalar VozLocal elimina de forma permanente y definitiva todos los datos locales y modelos de tu dispositivo.

---

## 3. Servicios y SDKs de Terceros

VozLocal no incluye librerías de terceros destinadas a recopilar información:
- **Sin SDKs de publicidad** (ni AdMob, ni similares).
- **Sin SDKs de análisis o telemetría** (ni Google Analytics, Firebase, etc.).
- **Sin herramientas de reporte de errores remoto** (ni Crashlytics, Sentry, etc.).
- **Sin APIs de IA en la nube** (ni OpenAI, ni Google Cloud Speech, etc.).

Todo el procesamiento corre a través de binarios de código abierto `whisper.cpp` y código Kotlin local.

---

## 4. Privacidad de Menores

VozLocal no está dirigida a menores de 13 años. Dado que VozLocal no recopila ni transmite ningún dato personal, tampoco recopila datos de menores.

---

## 5. Contacto

Para cualquier consulta sobre esta Política de Privacidad:
- **GitHub Issues:** [https://github.com/lander16/voz-local/issues](https://github.com/lander16/voz-local/issues)
- **Repositorio:** [https://github.com/lander16/voz-local](https://github.com/lander16/voz-local)

</details>
