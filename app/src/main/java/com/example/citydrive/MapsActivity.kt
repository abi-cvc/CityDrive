package com.example.citydrive

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import android.view.ViewGroup.MarginLayoutParams
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.example.citydrive.databinding.ActivityMapsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        leerIntentDeAeroGate()
        aplicarInsetsDeSistema()

        binding.btnSearch.setOnClickListener { buscarDestino() }
        binding.etSearchDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                buscarDestino()
                true
            } else {
                false
            }
        }

        binding.btnFinishTrip.isEnabled = false
        binding.btnFinishTrip.setOnClickListener { enviarAStaySmart() }

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
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

        // El pasajero también puede tocar el mapa para marcar su destino manualmente.
        mMap.setOnMapClickListener { latLng ->
            marcarDestino(latLng, "Destino seleccionado")
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

    private fun buscarDestino() {
        val query = binding.etSearchDestination.text.toString().trim()
        ocultarTeclado()
        if (query.isEmpty() || !Geocoder.isPresent()) return

        lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    Geocoder(this@MapsActivity, Locale.getDefault())
                        .getFromLocationName(query, 1)
                        ?.firstOrNull()
                } catch (e: Exception) {
                    null
                }
            }

            if (resultado != null) {
                val latLng = LatLng(resultado.latitude, resultado.longitude)
                marcarDestino(latLng, resultado.featureName ?: query)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
            } else {
                Toast.makeText(this@MapsActivity, "No se encontró \"$query\"", Toast.LENGTH_SHORT).show()
            }
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
