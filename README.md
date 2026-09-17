# FISIO SPORT TRACK Android Offline 1.2

Proyecto Android Studio para generar una APK instalable y autonoma de FISIO SPORT TRACK.

## Que incluye

- `app/src/main/assets/index.html`: HTML, CSS y JavaScript empaquetados dentro del APK.
- `app/src/main/assets/seed.json`: copia inicial de los datos actuales.
- `MainActivity`: WebView nativa que carga `file:///android_asset/index.html`.
- `NativeStore`: puente JavaScript nativo.
- SQLite local: guarda grupos, fichas, lesiones, sesiones, tratamientos y fotos en el dispositivo.
- Sistema de migraciones SQLite: futuras versiones del APK pueden actualizar el esquema sin borrar datos.
- Copias de seguridad locales: exporta e importa un unico archivo con todos los registros y fotografias.
- Revision 1.0: confirmaciones en acciones destructivas, validacion mas estricta de copias, edicion completa de sesiones y acabado visual unificado.

La app no carga ninguna URL de Vento y el manifiesto no solicita permiso de Internet.

## Compilar APK

1. Abre esta carpeta con Android Studio.
2. Espera a que sincronice Gradle.
3. Ejecuta `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
4. Instala el APK generado en la tablet.

## Funcionamiento

La primera vez que se abre, la app importa los datos de `seed.json` a SQLite.
Despues trabaja siempre sobre SQLite local del dispositivo.

La base de datos usa `SQLiteOpenHelper` con migraciones versionadas. Al instalar
una actualizacion del APK sobre una version anterior, Android conserva
`fisio_sport_track.db` y la app ejecuta automaticamente las migraciones
necesarias antes de abrir los datos. La semilla inicial no se vuelve a aplicar si
ya existen registros.

Conserva:

- Navegacion actual.
- Pantalla de inicio, club y pacientes privados.
- Tarjetas, fotos, dorsal, disponibilidad y filtros.
- Fichas de jugador.
- Lesiones, alta, historial y sesiones.
- Edicion y eliminacion de sesiones.
- Edicion de sesiones con alta, baja y modificacion de tratamientos dentro de una sesion existente.
- Selector de fotos.
- Exportacion completa de la base local a un archivo JSON de respaldo.
- Importacion/restauracion completa de una copia, reemplazando los datos actuales tras confirmacion y validacion de formato.
- Migraciones automaticas de base de datos para conservar datos en futuras actualizaciones.
- Ultimos ajustes del preview: fotografia de tarjetas corregida, alta de lesion funcional, Valoracion como tratamiento, seleccion de zonas acumulativa y eliminacion de fichas en jugadores y pacientes.
- Version 1.2: selector corporal anterior/posterior por musculos, buscador por seccion y navegacion atras dentro de la app.

## Limitaciones

Esta variante prioriza que la APK funcione completamente offline. No incluye sincronizacion real con Google Drive porque eso requiere configurar OAuth, permisos de Drive y resolucion de conflictos entre dispositivos.

Si se necesita sincronizar tablet y movil manteniendo APK offline, el siguiente paso correcto es anadir una integracion nativa de Drive o usar Capacitor con un plugin SQLite y un plugin Google Auth/Drive.

## Copias de seguridad

Desde Ajustes puedes usar:

- `Exportar copia`: abre el selector de Android para guardar un unico archivo `fisio-sport-track-backup.json` donde quieras. Incluye grupos, jugadores/pacientes, lesiones, sesiones, tratamientos y fotografias guardadas en los registros.
- `Importar copia`: abre el selector de Android para elegir una copia anterior. La restauracion reemplaza completamente los datos actuales de la app.

No requiere Internet ni ningun servicio externo.
