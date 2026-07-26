# CityDrive

Módulo de transporte del proyecto grupal **"Ruta de Entretenimiento y Turismo Urbano"**: una app nativa de Android (Kotlin) que integra el SDK de Google Maps y la API de Places para llevar al pasajero desde su punto de recogida hasta el destino que elija, comunicándose con el resto del ecosistema mediante Intents (deep links).

<p align="center">
  <img src="docs/Logo - CityDrive.png" alt="Logo CityDrive" width="200"/>
</p>

## Ecosistema grupal

El proyecto encadena 5 apps independientes, una por integrante, mediante Intents con esquema de deep link personalizado:

```
AeroGate ──(citydrive://request_ride)──▶ CityDrive ──(staysmart://auto_checkin)──▶ StaySmart ──▶ ArenaTick ──▶ CityPulse
```

CityDrive ocupa la posición 2: **recibe** de AeroGate (App de aeropuerto) los datos del pasajero y su punto de recogida, y **envía** a StaySmart (App de hospedaje) el destino elegido junto con los datos de la reserva de transporte.

| Dirección | Extra | Tipo | Descripción |
|---|---|---|---|
| AeroGate → CityDrive | `PARAM_ORIGEN_LAT` / `PARAM_ORIGEN_LNG` | Double | Coordenadas de recogida (aeropuerto) |
| AeroGate → CityDrive | `PARAM_PASAJERO_NOMBRE` | String | Nombre del pasajero |
| AeroGate → CityDrive | `PARAM_CAT_VEHICULO` | String | Categoría de vehículo solicitada |
| AeroGate → CityDrive | `PARAM_ID_VUELO` | String | Identificador del vuelo |
| CityDrive → StaySmart | `PARAM_DESTINO_LAT` / `PARAM_DESTINO_LNG` | Double | Coordenadas de llegada (hotel elegido) |
| CityDrive → StaySmart | `PARAM_ID_RESERVA` | String | ID de reserva de transporte, generado automáticamente |
| CityDrive → StaySmart | `PARAM_HUESPED_ID` | String | ID de huésped, generado automáticamente |
| CityDrive → StaySmart | `PARAM_TIMESTAMP_LLEGADA` | Long | Marca de tiempo de la llegada |

Los IDs de reserva/huésped se generan automáticamente dentro de CityDrive (no los escribe el usuario), tal como ocurriría en una app de transporte real.

## Funcionalidades

- Mapa con geolocalización real del dispositivo (`FusedLocationProviderClient`) y manejo de permisos de ubicación en tiempo de ejecución.
- Recepción del Intent de AeroGate con el punto de recogida y datos del pasajero, mostrado con un marcador dinámico.
- Búsqueda de lugares cercanos por palabra clave (Google Places API — Nearby Search) dentro de un radio configurable (2–50 km, por defecto 2 km) mediante un slider integrado en la barra de búsqueda.
- Selección de destino tocando un resultado de búsqueda o directamente un punto del mapa.
- Marcadores vectoriales propios por tipo: verde (recogida/ubicación real), naranja (destino elegido), morado (resultados de búsqueda).
- Deshacer la selección de destino con el botón "Atrás" en vez de cerrar la app (`OnBackPressedCallback`).
- Envío del destino confirmado a StaySmart mediante Intent, con aviso si la app no está instalada.

## Capturas de pantalla

| | | |
|---|---|---|
| ![Pantalla principal](docs/screenshots/02_pantalla_principal.png) | ![Búsqueda](docs/screenshots/03_escribiendo_busqueda.png) | ![Resultados](docs/screenshots/04_resultados_busqueda.png) |
| ![Destino seleccionado](docs/screenshots/05_destino_seleccionado.png) | ![Deshacer destino](docs/screenshots/06_deshacer_destino.png) | ![Intent de AeroGate](docs/screenshots/07_recepcion_intent_aerogate.png) |

## Tecnologías

- Kotlin + Android Views (`FragmentContainerView`, `ViewBinding`)
- Maps SDK for Android y Places API (Nearby Search)
- `FusedLocationProviderClient` (Google Play Services Location)
- Gradle version catalogs (`libs.versions.toml`) + `secrets-gradle-plugin` para la API key
- `compileSdk` 36, `minSdk` 24, `targetSdk` 36

## Configuración

1. Cloná el repo y abrilo en Android Studio.
2. Creá un archivo `local.properties` en la raíz del proyecto (si no existe) y agregá tu API key de Google Cloud con **Maps SDK for Android** y **Places API** habilitadas (y billing activo):

   ```properties
   MAPS_API_KEY=TU_API_KEY_AQUI
   ```

   Este archivo está en `.gitignore`; la key nunca debe subirse al repositorio.
3. Sincronizá Gradle y ejecutá la app en un dispositivo o emulador con Google Play Services.

## Compilar

```bash
./gradlew assembleDebug
```

El APK de debug se genera en `app/build/intermediates/apk/debug/app-debug.apk`.

## Probar la integración con AeroGate (sin tener AeroGate instalada)

```bash
adb shell am start -a android.intent.action.VIEW -d "citydrive://request_ride" \
  --ef PARAM_ORIGEN_LAT -0.1292 --ef PARAM_ORIGEN_LNG -78.3484 \
  --es PARAM_PASAJERO_NOMBRE Julian --es PARAM_CAT_VEHICULO VIP \
  --es PARAM_ID_VUELO AV-1234
```

## Documentación

El informe completo del proyecto (tema, objetivos, desarrollo técnico detallado, pruebas, conclusiones y bibliografía en formato IEEE) está en [`docs/INFORME.md`](docs/INFORME.md).

## Autora

Carol Velásquez — Escuela Politécnica Nacional (EPN)
