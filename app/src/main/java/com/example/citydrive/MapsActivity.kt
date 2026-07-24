package com.example.citydrive

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup.MarginLayoutParams
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.example.citydrive.databinding.ActivityMapsBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var binding: ActivityMapsBinding

    // Datos recibidos de AeroGate (App 1). Valores por defecto para poder
    // abrir y probar esta app sola, sin depender de que AeroGate esté instalada.
    private var origenLat = -0.1292
    private var origenLng = -78.3484
    private var pasajeroNombre = "Pasajero"
    private var categoriaVehiculo = "ECONOMY"
    private var idVuelo = "N/A"
    private var vinoDeAeroGate = false

    private var pickupMarker: Marker? = null
    private var destinoMarker: Marker? = null
    private var destinoSeleccionado: LatLng? = null

    private var radioKm = 2
    private val resultadosMarkers = mutableListOf<Marker>()
    private var ultimaBusqueda: List<LugarEncontrado> = emptyList()

    // Si hay un destino elegido, "atrás" lo deshace en vez de cerrar la app.
    private val deshacerDestinoCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            deshacerSeleccionDeDestino()
        }
    }

    // Ubicación GPS real y actual del dispositivo (no la de recogida). Se usa
    // como centro de la búsqueda de lugares. Null hasta que llega la primera lectura.
    private var ubicacionActual: LatLng? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val permisosDeUbicacionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permisos ->
        val concedido = permisos[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permisos[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (concedido) {
            habilitarUbicacionEnMapa()
        } else {
            Toast.makeText(
                this,
                "Sin permiso de ubicación: se usa el punto de recogida por defecto",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private data class LugarEncontrado(val nombre: String, val direccion: String, val latLng: LatLng)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        onBackPressedDispatcher.addCallback(this, deshacerDestinoCallback)

        leerIntentDeAeroGate()
        aplicarInsetsDeSistema()
        configurarBuscador()

        binding.btnFinishTrip.isEnabled = false
        binding.btnFinishTrip.setOnClickListener { enviarAStaySmart() }

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun configurarBuscador() {
        binding.tvRadius.text = getString(R.string.radius_label, radioKm)

        binding.seekRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // El SeekBar va de 0 a 48; lo desplazamos para representar un rango de 2 a 50 km.
                radioKm = progress + 2
                binding.tvRadius.text = getString(R.string.radius_label, radioKm)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnSearch.setOnClickListener { buscarLugares() }
        binding.etSearchDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                buscarLugares()
                true
            } else {
                false
            }
        }
    }

    // Con enableEdgeToEdge() el contenido dibuja detrás de las barras del sistema.
    // Ajustamos los márgenes de la barra de búsqueda y el botón para que no queden tapados.
    private fun aplicarInsetsDeSistema() {
        val margenBase = resources.getDimensionPixelSize(R.dimen.spacing_default)

        ViewCompat.setOnApplyWindowInsetsListener(binding.searchBar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<MarginLayoutParams> {
                topMargin = systemBars.top + margenBase
            }
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.btnFinishTrip) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = systemBars.bottom + margenBase
            }
            insets
        }
    }

    private fun ocultarTeclado() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearchDestination.windowToken, 0)
    }

    private fun leerIntentDeAeroGate() {
        val data: Uri? = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data != null) {
            vinoDeAeroGate = true
            origenLat = intent.getDoubleExtra("PARAM_ORIGEN_LAT", origenLat)
            origenLng = intent.getDoubleExtra("PARAM_ORIGEN_LNG", origenLng)
            pasajeroNombre = intent.getStringExtra("PARAM_PASAJERO_NOMBRE") ?: pasajeroNombre
            categoriaVehiculo = intent.getStringExtra("PARAM_CAT_VEHICULO") ?: categoriaVehiculo
            idVuelo = intent.getStringExtra("PARAM_ID_VUELO") ?: idVuelo
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        dibujarMarcadorRecogida(LatLng(origenLat, origenLng))
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(origenLat, origenLng), 14f))

        Toast.makeText(
            this,
            "Buscando conductor $categoriaVehiculo para $pasajeroNombre",
            Toast.LENGTH_LONG
        ).show()

        // El pasajero también puede tocar el mapa libremente para marcar su destino.
        mMap.setOnMapClickListener { latLng ->
            limpiarMarcadoresDeBusqueda()
            marcarDestino(latLng, "Destino seleccionado")
        }

        // Si toca uno de los marcadores morados (resultado de búsqueda), ese pasa a ser el destino.
        mMap.setOnMarkerClickListener { marker ->
            if (resultadosMarkers.contains(marker)) {
                seleccionarComoDestino(marker)
                true
            } else {
                false
            }
        }

        if (tienePermisoDeUbicacion()) {
            habilitarUbicacionEnMapa()
        } else {
            permisosDeUbicacionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun dibujarMarcadorRecogida(latLng: LatLng) {
        pickupMarker?.remove()
        pickupMarker = mMap.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Punto de recogida")
                .snippet("Pasajero: $pasajeroNombre | Vuelo: $idVuelo")
                .icon(bitmapDescriptorFromVector(R.drawable.ic_marker_pickup))
        )
    }

    private fun tienePermisoDeUbicacion(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Activa el punto azul y el botón nativo "mi ubicación" del mapa, y pide una
    // lectura de ubicación FRESCA (no la última en caché) para usarla como centro
    // de las búsquedas de lugares cercanos.
    @SuppressLint("MissingPermission")
    private fun habilitarUbicacionEnMapa() {
        mMap.isMyLocationEnabled = true
        mMap.uiSettings.isMyLocationButtonEnabled = true

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            CancellationTokenSource().token
        ).addOnSuccessListener { location ->
            if (location != null) {
                val actual = LatLng(location.latitude, location.longitude)
                ubicacionActual = actual

                // Si la app se abrió sola (sin datos de AeroGate), el punto de
                // recogida también se centra en la ubicación real del dispositivo.
                if (!vinoDeAeroGate) {
                    origenLat = actual.latitude
                    origenLng = actual.longitude
                    dibujarMarcadorRecogida(actual)
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(actual, 16f))
                }
            }
        }
    }

    private fun marcarDestino(latLng: LatLng, titulo: String) {
        destinoMarker?.remove()
        destinoMarker = mMap.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(titulo)
                .icon(bitmapDescriptorFromVector(R.drawable.ic_marker_destination))
        )
        destinoSeleccionado = latLng
        binding.btnFinishTrip.isEnabled = true
        deshacerDestinoCallback.isEnabled = true
    }

    private fun seleccionarComoDestino(marker: Marker) {
        val latLng = marker.position
        val nombre = marker.title ?: "Destino seleccionado"
        limpiarMarcadoresDeBusqueda()
        marcarDestino(latLng, nombre)
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
        Toast.makeText(this, "Destino: $nombre", Toast.LENGTH_SHORT).show()
    }

    // "Atrás" con un destino ya elegido: lo deshace y redibuja los resultados
    // morados de la última búsqueda, en vez de cerrar la aplicación.
    private fun deshacerSeleccionDeDestino() {
        destinoMarker?.remove()
        destinoMarker = null
        destinoSeleccionado = null
        binding.btnFinishTrip.isEnabled = false
        deshacerDestinoCallback.isEnabled = false

        ultimaBusqueda.forEach { lugar ->
            val marker = mMap.addMarker(
                MarkerOptions()
                    .position(lugar.latLng)
                    .title(lugar.nombre)
                    .snippet(lugar.direccion)
                    .icon(bitmapDescriptorFromVector(R.drawable.ic_marker_result))
            )
            marker?.let { resultadosMarkers.add(it) }
        }

        Toast.makeText(this, "Selección de destino deshecha", Toast.LENGTH_SHORT).show()
    }

    private fun limpiarMarcadoresDeBusqueda() {
        resultadosMarkers.forEach { it.remove() }
        resultadosMarkers.clear()
    }

    private fun buscarLugares() {
        val query = binding.etSearchDestination.text.toString().trim()
        ocultarTeclado()
        if (query.isEmpty()) return

        // Centrado en la ubicación real del dispositivo; si aún no hay una lectura GPS
        // (permiso recién concedido, sin señal, etc.), usamos el punto de recogida como respaldo.
        val centro = ubicacionActual ?: LatLng(origenLat, origenLng)
        val radioMetros = radioKm * 1000
        val radioBusqueda = radioKm

        lifecycleScope.launch {
            val lugares = withContext(Dispatchers.IO) {
                try {
                    buscarLugaresEnPlacesApi(query, centro, radioMetros)
                } catch (e: Exception) {
                    null
                }
            }

            limpiarMarcadoresDeBusqueda()

            if (lugares.isNullOrEmpty()) {
                Toast.makeText(
                    this@MapsActivity,
                    "No se encontraron lugares para \"$query\" en $radioBusqueda km",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            ultimaBusqueda = lugares

            lugares.forEach { lugar ->
                val marker = mMap.addMarker(
                    MarkerOptions()
                        .position(lugar.latLng)
                        .title(lugar.nombre)
                        .snippet(lugar.direccion)
                        .icon(bitmapDescriptorFromVector(R.drawable.ic_marker_result))
                )
                marker?.let { resultadosMarkers.add(it) }
            }

            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(lugares.first().latLng, 13f))
            Toast.makeText(
                this@MapsActivity,
                "${lugares.size} resultado(s). Toca un marcador morado para elegirlo como destino",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Places API - Nearby Search: a diferencia de Text Search, "location" + "radius"
    // aquí SÍ son un límite estricto (no solo una sugerencia de ranking), así que los
    // resultados quedan realmente contenidos dentro del radio elegido. "keyword" filtra
    // por texto libre ("hotel") dentro de ese círculo.
    // Se ejecuta en Dispatchers.IO porque hace una llamada de red bloqueante.
    private fun buscarLugaresEnPlacesApi(
        query: String,
        centro: LatLng,
        radioMetros: Int
    ): List<LugarEncontrado> {
        val url = URL(
            "https://maps.googleapis.com/maps/api/place/nearbysearch/json" +
                "?location=${centro.latitude},${centro.longitude}" +
                "&radius=$radioMetros" +
                "&keyword=" + URLEncoder.encode(query, "UTF-8") +
                "&key=${BuildConfig.MAPS_API_KEY}"
        )

        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val results = json.optJSONArray("results")

            val lugares = mutableListOf<LugarEncontrado>()
            if (results != null) {
                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val location = item.getJSONObject("geometry").getJSONObject("location")
                    lugares.add(
                        LugarEncontrado(
                            nombre = item.optString("name"),
                            direccion = item.optString(
                                "formatted_address",
                                item.optString("vicinity", "")
                            ),
                            latLng = LatLng(location.getDouble("lat"), location.getDouble("lng"))
                        )
                    )
                }
            }
            lugares
        } finally {
            connection.disconnect()
        }
    }

    private fun enviarAStaySmart() {
        val destino = destinoSeleccionado ?: return

        // El pasajero no conoce estos IDs: los genera CityDrive automáticamente.
        val idReserva = "CD-" + UUID.randomUUID().toString().take(8).uppercase()
        val huespedId = "USR-" + pasajeroNombre.hashCode().toString().takeLast(6)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("staysmart://auto_checkin")
            putExtra("PARAM_ID_RESERVA", idReserva)
            putExtra("PARAM_DESTINO_LAT", destino.latitude)
            putExtra("PARAM_DESTINO_LNG", destino.longitude)
            putExtra("PARAM_HUESPED_ID", huespedId)
            putExtra("PARAM_TIMESTAMP_LLEGADA", System.currentTimeMillis())
        }

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "Por favor, instala la app StaySmart", Toast.LENGTH_LONG).show()
        }
    }

    private fun bitmapDescriptorFromVector(vectorResId: Int): BitmapDescriptor {
        val drawable = ContextCompat.getDrawable(this, vectorResId)!!
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}
