package com.example.citydrive

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup.MarginLayoutParams
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.example.citydrive.databinding.ActivityMapsBinding
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

    private var destinoMarker: Marker? = null
    private var destinoSeleccionado: LatLng? = null

    private var radioKm = 10
    private val resultadosMarkers = mutableListOf<Marker>()

    private data class LugarEncontrado(val nombre: String, val direccion: String, val latLng: LatLng)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            origenLat = intent.getDoubleExtra("PARAM_ORIGEN_LAT", origenLat)
            origenLng = intent.getDoubleExtra("PARAM_ORIGEN_LNG", origenLng)
            pasajeroNombre = intent.getStringExtra("PARAM_PASAJERO_NOMBRE") ?: pasajeroNombre
            categoriaVehiculo = intent.getStringExtra("PARAM_CAT_VEHICULO") ?: categoriaVehiculo
            idVuelo = intent.getStringExtra("PARAM_ID_VUELO") ?: idVuelo
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        val origen = LatLng(origenLat, origenLng)
        mMap.addMarker(
            MarkerOptions()
                .position(origen)
                .title("Punto de recogida")
                .snippet("Pasajero: $pasajeroNombre | Vuelo: $idVuelo")
                .icon(bitmapDescriptorFromVector(R.drawable.ic_marker_pickup))
        )
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(origen, 14f))

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
    }

    private fun seleccionarComoDestino(marker: Marker) {
        val latLng = marker.position
        val nombre = marker.title ?: "Destino seleccionado"
        limpiarMarcadoresDeBusqueda()
        marcarDestino(latLng, nombre)
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
        Toast.makeText(this, "Destino: $nombre", Toast.LENGTH_SHORT).show()
    }

    private fun limpiarMarcadoresDeBusqueda() {
        resultadosMarkers.forEach { it.remove() }
        resultadosMarkers.clear()
    }

    private fun buscarLugares() {
        val query = binding.etSearchDestination.text.toString().trim()
        ocultarTeclado()
        if (query.isEmpty()) return

        val centro = LatLng(origenLat, origenLng)
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

    // Places API - Text Search: busca texto libre ("hotel") dentro de un radio en metros.
    // Se ejecuta en Dispatchers.IO porque hace una llamada de red bloqueante.
    private fun buscarLugaresEnPlacesApi(
        query: String,
        centro: LatLng,
        radioMetros: Int
    ): List<LugarEncontrado> {
        val url = URL(
            "https://maps.googleapis.com/maps/api/place/textsearch/json" +
                "?query=" + URLEncoder.encode(query, "UTF-8") +
                "&location=${centro.latitude},${centro.longitude}" +
                "&radius=$radioMetros" +
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
