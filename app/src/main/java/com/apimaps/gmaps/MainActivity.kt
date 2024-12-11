package com.apimaps.gmaps

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.apimaps.gmaps.ui.theme.GmapsTheme
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import com.google.android.gms.maps.model.BitmapDescriptorFactory


data class Ubicacion(val coordenadas: LatLng, val titulo: String, val descripcion: String)

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastLocation: LatLng? = null


    private var sendJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        enableEdgeToEdge()
        setContent {
            GmapsTheme {
                Surface {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        var ubicacionActual by remember { mutableStateOf<Ubicacion?>(null) }

                        LaunchedEffect(Unit) {
                            // permiso de ubicación
                            if (checkLocationPermission()) {
                                obtenerUbicacion { latLng ->
                                    ubicacionActual = Ubicacion(
                                        coordenadas = latLng,
                                        titulo = "Mi ubicación actual",
                                        descripcion = "ubicación en tiempo real"
                                    )
                                    lastLocation = latLng
                                    checkAndRequestNotificationPermission()
                                }
                            }
                        }

                        ubicacionActual?.let { lugar ->
                            Map(lugar) {}
                        }
                    }
                }
            }
        }
    }

    // Verifica y solicita el permiso de ubicación
    private fun checkLocationPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else ""
                ).filter { it.isNotEmpty() }.toTypedArray(),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            false
        } else {
            true
        }
    }

    // Verifica y solicita el permiso de notificaciones
    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            } else {
                // Permiso ya concedido
                startSendingLocationPeriodically()
            }
        } else {
            // Versiones anteriores
            startSendingLocationPeriodically()
        }
    }

    // rutina
    private fun startSendingLocationPeriodically() {

        sendJob?.cancel()

        sendJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                // Obtener ubicación actualizada
                getCurrentLocation()?.let { latLng ->
                    // Enviar a endpoint
                    enviarCoordenadasAEndpoint(latLng.latitude, latLng.longitude)

                    mostrarNotificacion(latLng)
                }
                delay(5000) // Espera 5 segundos
            }
        }
    }

    // Resultado de las solicitudes de permiso
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            // Revisión de permisos de ubicación
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permiso de ubicación concedido
                obtenerUbicacion { latLng ->
                    lastLocation = latLng
                    checkAndRequestNotificationPermission()
                }
            }
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            // permisos de notificación
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permiso de notificación concedido
                startSendingLocationPeriodically()
            } else {
                startSendingLocationPeriodically()
            }
        }
    }

    // Obtiene la ubicación actual
    @SuppressLint("MissingPermission")
    private fun obtenerUbicacion(onLocationReceived: (LatLng) -> Unit) {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                val latLng = LatLng(it.latitude, it.longitude)
                onLocationReceived(latLng)
            }
        }
    }

    // Obtener ubicación actual
    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocation(): LatLng? = suspendCancellableCoroutine { cont ->
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                cont.resume(LatLng(location.latitude, location.longitude), null)
            } else {
                cont.resume(null, null)
            }
        }
    }

    // Envía las coordenadas al endpoint vía POST
    private fun enviarCoordenadasAEndpoint(lat: Double, lng: Double) {
        val client = OkHttpClient()


        val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val jsonBody = """{"lat":$lat,"lng":$lng}"""
        val body = RequestBody.create(jsonMediaType, jsonBody)

        val request = Request.Builder()
            .url("https://miapi.com/ubicacion")
            .post(body)
            .build()

        // errores posibles
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // Manejar error
                    println("Error al enviar coordenadas: ${response.message}")
                } else {
                    println("Coordenadas enviadas correctamente.")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Método para mostrar una notificación
    @SuppressLint("MissingPermission")
    private fun mostrarNotificacion(latLng: LatLng) {
        val channelId = "ubicacion_channel"
        val notificationId = 1

        // para Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Notificaciones de Ubicación",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Muestra las coordenadas actuales"
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        // Crear
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Ubicación Actual")
            .setContentText("Lat: ${latLng.latitude}, Lng: ${latLng.longitude}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Mostrar
        with(NotificationManagerCompat.from(this)) {
            notify(notificationId, notification)
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002
    }
}

@Composable
fun Map(Ubicacion: Ubicacion, onReady: (GoogleMap) -> Unit) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // ciclo
    DisposableEffect(mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(Bundle())
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    // Mostrar el mapa
    AndroidView(
        factory = {
            mapView.apply {
                getMapAsync { googleMap ->
                    val zoomLevel = 15f
                    googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(Ubicacion.coordenadas, zoomLevel))
                    googleMap.addMarker(
                        MarkerOptions() //El Marquer recibe coordenadas y texto
                            .position(Ubicacion.coordenadas)
                            .title(Ubicacion.titulo)
                            .snippet(Ubicacion.descripcion)
                            .icon(BitmapDescriptorFactory.fromResource(R.drawable.moto)) //Icono de motocicleta
                    )
                    onReady(googleMap)
                }
            }
        }
    )
}