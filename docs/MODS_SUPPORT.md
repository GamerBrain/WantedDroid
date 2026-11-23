# Soporte de mods (fork Android Evolved)

Este fork añade una base de **mods por superposición de archivos (overlay)** para el runtime Android recompilado de NFSMW 2005.

## Estado actual

- Capa nativa en `android/app/src/main/cpp/guest_files.inc` que resuelve lecturas/búsquedas del juego priorizando mods habilitados y luego los datos base (`nfsmw/`).
- Escrituras, perfiles y mutaciones se dirigen a los datos base para no alterar paquetes de mod.
- `FindFirstFile/FindNextFile` combina resultados de mods y base (el mod de mayor prioridad gana por nombre), mejorando compatibilidad con mods que añaden o reemplazan archivos.
- Caché de entradas de directorio para reducir coste de resolución case-insensitive (útil en almacenamiento Android/FUSE y con overlays).
- Gestor/UI en el launcher (`ModManager`, `LauncherActivity`): instalar `.zip`, activar/desactivar, cambiar prioridad (orden de activados), eliminar y persistir configuración.
- Configuración: `nfsmw/Mods/mods.json` (launcher) y `nfsmw/Mods/mods.conf` (lista simple para el runtime nativo, una carpeta por línea, mayor prioridad primero).

## Uso

1. Copia o instala mods en `nfsmw/Mods/<NombreDelMod>/` con la misma estructura de carpetas/archivos que el juego (`GLOBAL`, `CARS`, `TRACKS`, `TEXTURES`, `SOUND`, etc.).
2. En el launcher abre **Mods** para instalar un `.zip`, activar/desactivar o eliminar mods.
3. Pulsa **JUGAR**: los mods habilitados se cargan al iniciar el proceso del juego.

## Metadatos opcionales

Un mod puede incluir `mod.json` o `nfsmw_mod.json` en su raíz, por ejemplo:

```json
{
  "name": "Mi mod",
  "version": "1.0",
  "author": "Autor",
  "description": "Texturas HD",
  "category": "textures"
}
```

## Alcance y limitaciones

- **Soportado (base):** mods de reemplazo/overlay de archivos existentes o añadidos que el juego carga por rutas del sistema de archivos (texturas, gráficos, UI, audio, datos preparados, coches/vinilos/body kits cuando vienen como archivos compatibles ya integrados para esta versión/datos).
- **No garantizado automáticamente:** conversión/instalación automática de todos los formatos de mods de PC (por ejemplo ediciones VLT/AttribSys/BIN, scripts/frontend de coches añadidos, instaladores específicos). Algunos mods de "coches añadibles", vinilos o body kits pueden requerir preparación/adaptación previa compatible con los datos de NFSMW 2005 que usa este recompilado.
- Los mods se aplican por overlay en tiempo de carga/lectura; no hay desinstalación "quirúrgica" dentro de los datos base porque el base no se modifica por el mod.
- La estabilidad y compatibilidad dependen del mod y del dispositivo. En Unisoc T616 / Mali-G57 se recomienda el perfil estable del launcher y probar mods de uno en uno si hay fallos.

## Convivencia con Rust (optimización incremental)

`rust/nfsmw_mods/` contiene un crate base en Rust con validación de rutas/nombres de mods, modelo de manifiesto/config y exportaciones C FFI (`nfsmw_mods_is_safe_folder_name`, `nfsmw_mods_is_safe_relative_path`, header `include/nfsmw_mods.h`).

El build nativo Android puede enlazar una librería Rust precompilada por ABI con CMake (`-DNFSMW_RUST_MODS_LIB=...`), definiendo `NFS_RUST_MODS` para usar validación en Rust desde C/C++ (por ejemplo en la carga de `mods.conf`). Esto permite mejorar seguridad/eficiencia de forma incremental sin reescribir el runtime completo.
