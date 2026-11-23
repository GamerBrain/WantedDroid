# Cómo generar un APK funcional (jugable) de este fork

Los APKs de prueba publicados en este fork (`WantedDroid-Beta1.1.apk`, etc.) son **solo launcher/interfaz** (`-PnfsLauncherOnly=true`): permiten probar UI, opciones, gestor de mods e información de GPU/Vulkan, pero **no** incluyen el runtime nativo del juego recompilado y por tanto **no pueden ejecutar la partida**.

Para que el botón **Jugar** ejecute el juego en el teléfono se necesita un **APK completo** compilado con las librerías nativas y los módulos del recompilado.

## Requisitos principales

1. **Código/módulos recompilados generados** (no incluidos en el repositorio):
   - `src/recomp/gen/` con `recomp_*.c` (guest recompilado del juego).
   - `src/recomp/gen_server/` (traducción de `server.dll`).
   - `src/recomp/gen_d3dx/` (traducción de `d3dx9_26.dll`).
   - `src/recomp/gen_abk/` (datos/sonido `.abk` generados).
   La compilación Android falla si faltan (ver [android/app/src/main/cpp/CMakeLists.txt](../android/app/src/main/cpp/CMakeLists.txt)).

2. **Pipeline de recompilación** para generar esos módulos:
   - Herramientas/proyecto de recompilación (p. ej. `pcrecomp`) y scripts del proyecto.
   - Entradas requeridas por el pipeline (binarios/datos originales de la versión de PC objetivo). Estos artefactos se generan en un entorno de compilación (normalmente en PC), no en el teléfono.

3. **Dependencias y toolchain Android**:
   - Android SDK + NDK + CMake + Build-Tools (Java/JDK compatible con Gradle/AGP del proyecto).
   - Repos/dependencias externos esperados por el build (por ejemplo `pcrecomp`, y dependencias usadas por este port como DXVK/adrenotools según configuración/rutas en CMake).

4. **(Opcional) Rust para validación/ayudas de mods en nativo**:
   - Compilar `rust/nfsmw_mods` como `staticlib` por ABI Android (`aarch64-linux-android`, `armv7-linux-androideabi` según corresponda) y pasar `-DNFSMW_RUST_MODS_LIB=<ruta>` al CMake/Gradle para definir `NFS_RUST_MODS`.

## Compilación (esquema)

- Preparar/generar `src/recomp/gen*`, `gen_server`, `gen_d3dx`, `gen_abk` y ubicar dependencias según las rutas que espera el CMake.
- Desde [`android/`](../android/): configurar `ANDROID_HOME`/`ANDROID_SDK_ROOT` y ejecutar `./gradlew :app:assembleDebug` o `./gradlew :app:assembleRelease` (build **sin** `-PnfsLauncherOnly=true`).
- El APK resultante es el que puede iniciar el juego y aplicar, en tiempo de ejecución, el sistema de mods por overlay implementado en este fork (cuando los mods son compatibles con el formato/estructura de datos de esta versión).

## Archivos del juego en el teléfono (no se incluyen en el APK)

El APK completo detecta los datos del juego desde el almacenamiento interno:

- Ruta: **`Almacenamiento interno/nfsmw`** (`/storage/emulated/0/nfsmw`)
- Coloca directamente en esa carpeta: `Speed.exe`, `GLOBAL/`, `TRACKS/`, `CARS/`, `FRONTEND/`, `LANGUAGES/`, etc. (sin anidar `nfsmw/nfsmw/...`).
- Concede permiso de acceso a archivos en el launcher y verifica que los checks aparezcan en verde.

## Nota sobre pruebas sin artefactos generados

Si no dispones de los módulos generados ni del entorno de pipeline, no es posible compilar desde este repositorio un APK que ejecute el juego. Como alternativa temporal para validar que los datos están bien ubicados y que el juego arranca en tu dispositivo, puedes instalar un APK funcional publicado por el proyecto base y colocar los archivos en `Almacenamiento interno/nfsmw/` (ten en cuenta que ese APK no incluirá los cambios específicos de este fork, como el sistema de mods/gestor implementado aquí).
