package com.sofawander.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.Bitmap
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.Spanned
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sofawander.app.data.AppDatabase
import com.sofawander.app.data.FavoriteEntity
import com.sofawander.app.data.RouteEntity
import com.sofawander.app.data.RouteFileIO
import com.sofawander.app.data.RouteJson
import com.sofawander.app.data.RoutePoint
import com.sofawander.app.databinding.ActivityMainBinding
import com.sofawander.app.ui.FavoriteAdapter
import com.sofawander.app.ui.FavoriteItem
import com.sofawander.app.ui.GpsEventAdapter
import com.sofawander.app.ui.GpsEventItem
import com.sofawander.app.ui.RunHistoryAdapter
import com.sofawander.app.ui.RunHistoryItem
import com.sofawander.app.ui.RouteAdapter
import com.sofawander.app.ui.RouteItem
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.utils.BitmapUtils
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.gson.Gson
import com.google.android.gms.location.LocationServices
import android.graphics.drawable.Drawable
import android.view.inputmethod.EditorInfo
import android.location.Geocoder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private var mapLibre: MapLibreMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val routePoints = mutableListOf<RoutePoint>()
    private lateinit var adapter: RouteAdapter
    private var selectedRouteId: Long? = null
    private lateinit var favoriteAdapter: FavoriteAdapter
    private lateinit var historyAdapter: RunHistoryAdapter
    private var selectedFavoriteId: Long? = null
    private var selectedPointSource: GeoJsonSource? = null
    private var lastTappedPoint: RoutePoint? = null
    private var currentMockLat: Double = 0.0
    private var currentMockLng: Double = 0.0

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val input = contentResolver.openInputStream(uri) ?: return@registerForActivityResult
            input.use { raw ->
                val stream = java.io.BufferedInputStream(raw)
                stream.mark(512)
                try {
                    val buffer = ByteArray(512)
                    val read = stream.read(buffer)
                    val textSample = if (read > 0) String(buffer, 0, read, Charsets.UTF_8) else ""
                    stream.reset()
                    val points = when {
                        textSample.contains("<gpx", ignoreCase = true) -> RouteFileIO.parseGpx(stream)
                        textSample.contains("<kml", ignoreCase = true) -> RouteFileIO.parseKml(stream)
                        else -> null
                    }
                    if (points == null) {
                        binding.textError.setText(R.string.error_import_unsupported)
                        return@use
                    }
                    if (points.isNotEmpty()) {
                        routePoints.clear()
                        routePoints.addAll(points)
                        updateRouteLine()
                        updatePointCount()
                        showImportSummary(points.size)
                    } else {
                        binding.textError.setText(R.string.error_import_empty)
                    }
                } catch (_: Exception) {
                    binding.textError.setText(R.string.error_import_failed)
                }
            }
        }

    private val createGpxLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
            if (uri == null) return@registerForActivityResult
            if (routePoints.size < 2) {
                binding.textError.setText(R.string.error_route_export_points)
                return@registerForActivityResult
            }
            val output = contentResolver.openOutputStream(uri) ?: run {
                binding.textError.setText(R.string.error_export_failed)
                return@registerForActivityResult
            }
            output.use { stream ->
                try {
                    val name = "SofaWander Route"
                    RouteFileIO.writeGpx(stream, name, routePoints)
                } catch (_: Exception) {
                    binding.textError.setText(R.string.error_export_failed)
                }
            }
        }

    private val createKmlLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { uri ->
            if (uri == null) return@registerForActivityResult
            if (routePoints.size < 2) {
                binding.textError.setText(R.string.error_route_export_points)
                return@registerForActivityResult
            }
            val output = contentResolver.openOutputStream(uri) ?: run {
                binding.textError.setText(R.string.error_export_failed)
                return@registerForActivityResult
            }
            output.use { stream ->
                try {
                    val name = "SofaWander Route"
                    RouteFileIO.writeKml(stream, name, routePoints)
                } catch (_: Exception) {
                    binding.textError.setText(R.string.error_export_failed)
                }
            }
        }
    private var isDraggingPoint = false
    private var draggingIndex = -1
    private var isRouteRunning = false

    private val mockStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(MockLocationService.EXTRA_STATUS) ?: return
            val message = intent.getStringExtra(MockLocationService.EXTRA_MESSAGE).orEmpty()
            android.util.Log.d("MockApp", "Status received: $status")
            binding.textStatus.text = status
            binding.textError.text = message

            val running = status == getString(R.string.status_running) || status == getString(R.string.status_paused)
            isRouteRunning = running
            binding.buttonStartRoute.text = if (running) "Stop Route" else "Start Route"
            binding.layoutPlaybackStats.visibility = if (running) View.VISIBLE else View.GONE
        }
    }

    private val mockProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val traveled = intent.getDoubleExtra(MockLocationService.EXTRA_DISTANCE_TRAVELED, 0.0)
            val total = intent.getDoubleExtra(MockLocationService.EXTRA_TOTAL_DISTANCE, 0.0)
            val elapsedMs = intent.getLongExtra(MockLocationService.EXTRA_ELAPSED_MS, 0L)
            val speedKmh = intent.getDoubleExtra(MockLocationService.EXTRA_CURRENT_SPEED_KMH, 0.0)
            
            // 獲取目前點位置（用於跳轉對話框距離顯示）
            val lat = intent.getDoubleExtra(MockLocationService.EXTRA_LAT, 0.0)
            val lng = intent.getDoubleExtra(MockLocationService.EXTRA_LNG, 0.0)
            if (lat != 0.0) {
                currentMockLat = lat
                currentMockLng = lng
            }

            binding.textPlaybackDistance.text = formatDistance(traveled) + " / " + formatDistance(total)

            val elapsedStr = formatDuration(elapsedMs)
            val etaMs = if (traveled > 0) {
                ((total - traveled) / (traveled / elapsedMs)).toLong()
            } else 0L
            val etaStr = formatDuration(elapsedMs + etaMs)
            binding.textPlaybackTime.text = "$elapsedStr / $etaStr"

            binding.textPlaybackSpeed.text = "%.1f km/h".format(speedKmh)
            
            // 確保行走跳轉時也會顯示底部統計條
            if (binding.layoutPlaybackStats.visibility != View.VISIBLE) {
                android.util.Log.d("MockApp", "Showing stats bar via progress update")
                binding.layoutPlaybackStats.visibility = View.VISIBLE
            }
        }
    }

    private val mockRouteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(MockLocationService.EXTRA_ROUTE_JSON) ?: return
            android.util.Log.d("MockApp", "Route received, length: ${json.length}")
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<RoutePoint>>() {}.type
                val points: List<RoutePoint> = Gson().fromJson(json, type)
                
                if (points.isNotEmpty()) {
                    routePoints.clear()
                    routePoints.addAll(points)
                    updateRouteLine()
                    
                    // 接收到路徑時，強迫顯示統計條 (適用于 Walk Mode)
                    if (binding.layoutPlaybackStats.visibility != View.VISIBLE) {
                        binding.layoutPlaybackStats.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MockApp", "Error parsing walk route", e)
            }
        }
    }

    private fun formatDistance(meters: Double): String {
        return if (meters >= 1000) "%.2f km".format(meters / 1000.0)
        else "%.0f m".format(meters)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (min >= 60) {
            val hr = min / 60
            "%d:%02d:%02d".format(hr, min % 60, sec)
        } else {
            "%02d:%02d".format(min, sec)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this, null, WellKnownTileServer.MapLibre)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        mapView = binding.mapView
        mapView.onCreate(savedInstanceState)
        setupMap()
        updatePointCount()

        adapter = RouteAdapter { item ->
            loadRoute(item)
        }
        binding.recyclerRoutes.layoutManager = LinearLayoutManager(this)
        binding.recyclerRoutes.adapter = adapter

        // favorites are now handled inside a custom dialog when opened

        historyAdapter = RunHistoryAdapter { item ->
            showRunDetails(item)
        }
        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = historyAdapter

        observeRoutes()
        observeFavorites()
        observeHistory()
        bindActions()
        setupInputFilters()
        setupTooltips()
        initM4Defaults()
        setupJoystick()

        // 註冊 MockLocationService 狀態接收器
        val statusFilter = android.content.IntentFilter(MockLocationService.ACTION_MOCK_STATUS)
        val progressFilter = android.content.IntentFilter(MockLocationService.ACTION_MOCK_PROGRESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mockStatusReceiver, statusFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(mockProgressReceiver, progressFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mockStatusReceiver, statusFilter)
            registerReceiver(mockProgressReceiver, progressFilter)
        }

        // Android 13+ 通知權限動態請求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
            }
        }
    }

    private fun bindActions() {
        binding.buttonDevOptions.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            startActivity(intent)
        }

        binding.buttonLocationSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        }

        binding.buttonStart.setOnClickListener {
            ensurePermissionsAndStart()
        }

        binding.buttonPause.setOnClickListener {
            pauseRoutePlayback()
        }

        binding.buttonStop.setOnClickListener {
            stopRoutePlayback()
        }

        binding.spinnerSpeedMode.setOnItemSelectedListener(
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    applySpeedDefaultsIfEmpty(position)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        )

        binding.buttonPresetWalk.setOnClickListener {
            applySpeedPreset(0)
            binding.spinnerSpeedMode.setSelection(0)
        }

        binding.buttonPresetJog.setOnClickListener {
            applySpeedPreset(1)
            binding.spinnerSpeedMode.setSelection(1)
        }

        binding.buttonPresetDrive.setOnClickListener {
            applySpeedPreset(2)
            binding.spinnerSpeedMode.setSelection(2)
        }

        binding.buttonPresetPauseNone.setOnClickListener {
            applyPausePreset(0)
        }

        binding.buttonPresetPauseShort.setOnClickListener {
            applyPausePreset(1)
        }

        binding.buttonPresetPauseLong.setOnClickListener {
            applyPausePreset(2)
        }

        binding.buttonClearPause.setOnClickListener {
            binding.editPauseMin.text?.clear()
            binding.editPauseMax.text?.clear()
        }

        binding.checkDrift.setOnCheckedChangeListener { _, isChecked ->
            binding.editDriftMeters.isEnabled = isChecked
        }

        binding.checkBounce.setOnCheckedChangeListener { _, isChecked ->
            binding.editBounceMeters.isEnabled = isChecked
        }

        binding.checkSmoothing.setOnCheckedChangeListener { _, isChecked ->
            binding.editSmoothingAlpha.isEnabled = isChecked
        }

        binding.checkRoundTrip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.checkLoop.isChecked = false
            }
        }

        attachRangeWatchers()

        binding.buttonSaveRoute.setOnClickListener {
            saveRoute()
        }

        binding.buttonClearRoute.setOnClickListener {
            routePoints.clear()
            selectedRouteId = null
            updateRouteLine()
            updatePointCount()
        }

        binding.buttonUndoPoint.setOnClickListener {
            if (routePoints.isNotEmpty()) {
                routePoints.removeAt(routePoints.lastIndex)
                updateRouteLine()
                updatePointCount()
            }
        }

        binding.buttonStartRoute.setOnClickListener {
            if (isRouteRunning) {
                stopRoutePlayback()
                isRouteRunning = false
                binding.buttonStartRoute.text = "Start Route"
            } else {
                ensurePermissionsAndStart()
                // isRouteRunning 將在收到 STATUS_RUNNING 廣播後設定
            }
        }

        binding.buttonLoadRoute.setOnClickListener {
            showLoadRouteDialog()
        }

        binding.btnRouteEditorClose.setOnClickListener {
            binding.layoutRouteEditor.visibility = android.view.View.GONE
        }

        binding.btnRoutePlanning.setOnClickListener {
            val visible = binding.layoutRouteEditor.visibility == android.view.View.VISIBLE
            binding.layoutRouteEditor.visibility = if (visible) android.view.View.GONE else android.view.View.VISIBLE
        }

        binding.btnFavorites.setOnClickListener {
            showFavoritesDialog()
        }

        binding.btnSettings.setOnClickListener {
            binding.drawerLayout.open()
        }

        binding.btnWalkMenu.setOnClickListener {
            val visible = binding.layoutWalkControls.visibility == android.view.View.VISIBLE
            binding.layoutWalkControls.visibility = if (visible) android.view.View.GONE else android.view.View.VISIBLE
        }

        binding.btnTeleport.setOnClickListener {
            showTeleportDialog()
        }

        binding.btnLocation.setOnClickListener {
            centerToCurrentLocation()
        }

        binding.btnSearch.setOnClickListener {
            performSearch()
        }

        binding.editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }
    }

    private fun setupMap() {
        mapView.getMapAsync { map ->
            mapLibre = map
            map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                setupMapLayers(style)
                enableLocationComponent(style)
            }

            map.addOnMapClickListener { point ->
                if (binding.layoutRouteEditor.visibility == android.view.View.VISIBLE) {
                    routePoints.add(RoutePoint(point.latitude, point.longitude))
                    lastTappedPoint = RoutePoint(point.latitude, point.longitude)
                    updateRouteLine()
                    updatePointCount()
                } else {
                    // 非編輯模式下，點擊地圖即彈出跳轉
                    updateSelectedPointSource(point)
                    showTeleportDialog(point)
                }
                true
            }

            map.addOnMapLongClickListener { point ->
                if (binding.layoutRouteEditor.visibility == android.view.View.VISIBLE) {
                    if (!tryStartDrag(point.latitude, point.longitude)) {
                        removeNearestPoint(point.latitude, point.longitude)
                    }
                }
                true
            }

            centerToCurrentLocation()
        }

        mapView.setOnTouchListener { _, event ->
            if (!isDraggingPoint) return@setOnTouchListener false
            val map = mapLibre ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val latLng = map.projection.fromScreenLocation(PointF(event.x, event.y))
                    if (draggingIndex in routePoints.indices) {
                        routePoints[draggingIndex] = RoutePoint(latLng.latitude, latLng.longitude)
                        updateRouteLine()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopDrag()
                    true
                }
                else -> false
            }
        }
    }

    private var joystickCenterX = 0f
    private var joystickCenterY = 0f
    private var joystickRadius = 0f

    private fun setupJoystick() {
        val container = binding.joystickContainer
        val thumb = binding.joystickThumb

        container.post {
            joystickCenterX = container.width / 2f
            joystickCenterY = container.height / 2f
            joystickRadius = container.width / 2f - thumb.width / 2f
            
            thumb.translationX = 0f
            thumb.translationY = 0f
        }

        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - joystickCenterX
                    val dy = event.y - joystickCenterY
                    val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                    if (distance <= joystickRadius) {
                        thumb.translationX = dx
                        thumb.translationY = dy
                    } else {
                        val ratio = joystickRadius / distance
                        thumb.translationX = dx * ratio
                        thumb.translationY = dy * ratio
                    }
                    
                    // TODO: Dispatch joystick vector (dx, dy) to mock location service
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    thumb.animate().translationX(0f).translationY(0f).setDuration(150).start()
                    // TODO: Stop sending joystick vector
                    return@setOnTouchListener true
                }
            }
            false
        }
    }

    private fun updateRouteLine() {
        val map = mapLibre ?: return
        val style = map.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID) ?: return
        val pointSource = style.getSourceAs<GeoJsonSource>(POINTS_SOURCE_ID) ?: return

        val points = routePoints.map { Point.fromLngLat(it.longitude, it.latitude) }

        if (routePoints.size < 2) {
            source.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(arrayOf()))
        } else {
            source.setGeoJson(LineString.fromLngLats(points))
        }

        val features = points.map { point -> org.maplibre.geojson.Feature.fromGeometry(point) }
        pointSource.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(features))
    }

    @SuppressWarnings("MissingPermission")
    private fun enableLocationComponent(loadedMapStyle: Style) {
        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val locationComponent = mapLibre?.locationComponent
            val locationComponentActivationOptions =
                org.maplibre.android.location.LocationComponentActivationOptions.builder(this, loadedMapStyle)
                    .build()
            locationComponent?.activateLocationComponent(locationComponentActivationOptions)
            locationComponent?.isLocationComponentEnabled = true
            locationComponent?.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS
        }
    }

    private fun setupMapLayers(style: Style) {
        // 登錄圖示
        getBitmapFromVectorDrawable(this, R.drawable.ic_route_pin)?.let { bitmap ->
            style.addImage("route-pin-icon", bitmap)
        }
        getBitmapFromVectorDrawable(this, R.drawable.ic_route_arrow)?.let { bitmap ->
            style.addImage("route-arrow-icon", bitmap)
        }

        if (style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID))
        }
        if (style.getSourceAs<GeoJsonSource>(POINTS_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(POINTS_SOURCE_ID))
        }

        if (style.getLayer(ROUTE_LAYER_ID) == null) {
            val lineLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(MAP_ROUTE_COLOR),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND)
            )
            style.addLayer(lineLayer)
        }

        // 加入沿著路徑方向的路徑箭頭 (Arrow)
        if (style.getLayer(ROUTE_ARROW_LAYER_ID) == null) {
            val arrowLayer = org.maplibre.android.style.layers.SymbolLayer(
                ROUTE_ARROW_LAYER_ID,
                ROUTE_SOURCE_ID
            ).withProperties(
                PropertyFactory.symbolPlacement(org.maplibre.android.style.layers.Property.SYMBOL_PLACEMENT_LINE),
                PropertyFactory.iconImage("route-arrow-icon"),
                PropertyFactory.iconSize(0.8f),
                PropertyFactory.symbolSpacing(50f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
            style.addLayerAbove(arrowLayer, ROUTE_LAYER_ID)
        }

        // 以圖標 (Pin) 替代原本的 CircleLayer 作為點擊節點
        if (style.getLayer(POINTS_LAYER_ID) == null) {
            val pointLayer = org.maplibre.android.style.layers.SymbolLayer(
                POINTS_LAYER_ID,
                POINTS_SOURCE_ID
            ).withProperties(
                PropertyFactory.iconImage("route-pin-icon"),
                PropertyFactory.iconSize(0.6f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconOffset(arrayOf(0f, -15f))
            )
            style.addLayerAbove(pointLayer, ROUTE_ARROW_LAYER_ID)
        }

        // --- 加入單點選取的 Pin 點圖層 ---
        getBitmapFromVectorDrawable(this, R.drawable.ic_location)?.let { bitmap ->
            style.addImage(ICON_ID_SELECTED, bitmap)
        }
        if (style.getSourceAs<GeoJsonSource>(SOURCE_ID_SELECTED) == null) {
            style.addSource(GeoJsonSource(SOURCE_ID_SELECTED))
        }
        if (style.getLayer(LAYER_ID_SELECTED) == null) {
            val selectedLayer = SymbolLayer(LAYER_ID_SELECTED, SOURCE_ID_SELECTED).withProperties(
                PropertyFactory.iconImage(ICON_ID_SELECTED),
                PropertyFactory.iconSize(1.0f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconOffset(arrayOf(0f, -18f)),
                PropertyFactory.iconColor(android.graphics.Color.RED)
            )
            style.addLayer(selectedLayer)
        }
    }

    private fun updateSelectedPointSource(latLng: org.maplibre.android.geometry.LatLng?) {
        val map = mapLibre ?: return
        val style = map.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID_SELECTED) ?: return
        if (latLng == null) {
            source.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(arrayOf()))
        } else {
            lastTappedPoint = RoutePoint(latLng.latitude, latLng.longitude)
            source.setGeoJson(org.maplibre.geojson.Feature.fromGeometry(Point.fromLngLat(latLng.longitude, latLng.latitude)))
        }
    }

    private fun getBitmapFromVectorDrawable(context: Context, drawableId: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, drawableId) ?: return null
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun loadRoute(item: RouteItem) {
        selectedRouteId = item.id
        routePoints.clear()
        routePoints.addAll(item.points)
        updateRouteLine()
        updatePointCount()
        
        item.points.firstOrNull()?.let { point ->
            mapLibre?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    org.maplibre.android.geometry.LatLng(point.latitude, point.longitude),
                    15.0
                )
            )
        }
    }

    private var currentRoutes = emptyList<RouteItem>()

    private fun observeRoutes() {
        val db = AppDatabase.getInstance(this)
        lifecycleScope.launch {
            db.routeDao().getAllRoutes().collectLatest { routes ->
                currentRoutes = routes.map { entity ->
                    RouteItem(
                        id = entity.id,
                        name = entity.name,
                        points = RouteJson.fromJson(entity.pointsJson),
                        createdAt = entity.createdAt
                    )
                }
                adapter.submitList(currentRoutes)
            }
        }
    }

    private fun showLoadRouteDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_favorites, null) // 借用 favorites 的 dialog 佈局或以同樣結構撰寫
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerDialogFavorites)
        val btnCancel = view.findViewById<android.widget.Button>(R.id.btnCancelFavorites)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Load Route")
            .setView(view)
            .setCancelable(true)
            .create()

        val routeAdapter = RouteAdapter(
            onClick = { item ->
                loadRoute(item)
                dialog.dismiss()
            }
        )
        
        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        recycler.adapter = routeAdapter
        routeAdapter.submitList(currentRoutes)

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private var currentFavorites = emptyList<FavoriteItem>()

    private fun observeFavorites() {
        val db = AppDatabase.getInstance(this)
        lifecycleScope.launch {
            db.favoriteDao().getAllFavorites().collectLatest { favorites ->
                currentFavorites = favorites.map { entity ->
                    FavoriteItem(
                        id = entity.id,
                        name = entity.name,
                        lat = entity.lat,
                        lng = entity.lng,
                        note = entity.note
                    )
                }
            }
        }
    }

    private fun showFavoritesDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_favorites, null)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerDialogFavorites)
        val btnCancel = view.findViewById<android.widget.Button>(R.id.btnCancelFavorites)

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        val adapter = FavoriteAdapter(
            onClick = { item ->
                selectedFavoriteId = item.id
                lastTappedPoint = RoutePoint(item.lat, item.lng)
                mapLibre?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        org.maplibre.android.geometry.LatLng(item.lat, item.lng),
                        15.5
                    )
                )
                dialog.dismiss()
            },
            onDelete = { item ->
                val db = AppDatabase.getInstance(this)
                lifecycleScope.launch {
                    db.favoriteDao().deleteById(item.id)
                }
            }
        )
        
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        adapter.submitList(currentFavorites)

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun observeHistory() {
        val db = AppDatabase.getInstance(this)
        lifecycleScope.launch {
            db.runHistoryDao().getAllRuns().collectLatest { runs ->
                val items = runs.map { run ->
                    RunHistoryItem(
                        id = run.id,
                        routeName = run.routeName,
                        pointCount = run.pointCount,
                        speedMode = run.speedMode,
                        loopEnabled = run.loopEnabled,
                        roundTripEnabled = run.roundTripEnabled,
                        startedAt = run.startedAt,
                        endedAt = run.endedAt,
                        status = run.status
                    )
                }
                historyAdapter.submitList(items)
            }
        }
    }

    private fun showRunDetails(item: RunHistoryItem) {
        val db = AppDatabase.getInstance(this)
        lifecycleScope.launch {
            val events = db.gpsEventDao().getEventsForRun(item.id).first()
            val eventItems = events.map {
                GpsEventItem(
                    timestamp = it.timestamp,
                    lat = it.lat,
                    lng = it.lng,
                    accuracy = it.accuracy,
                    speedMps = it.speedMps
                )
            }
            renderRunDialog(item, eventItems)
        }
    }

    private fun showImportSummary(pointCount: Int) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_import_success))
            .setMessage(getString(R.string.message_import_points, pointCount))
            .setPositiveButton(getString(R.string.button_save_as_route)) { _, _ ->
                saveRoute()
            }
            .setNeutralButton(getString(R.string.button_keep_temp), null)
            .setNegativeButton(getString(R.string.button_cancel), null)
            .show()
    }

    private fun renderRunDialog(item: RunHistoryItem, events: List<GpsEventItem>) {
        val count = events.size
        val duration = if (item.endedAt != null) {
            ((item.endedAt - item.startedAt) / 1000).coerceAtLeast(0)
        } else null
        val latest = events.lastOrNull()
        val summary = StringBuilder().apply {
            append("Points: ${item.pointCount}\n")
            append("Events: $count\n")
            if (duration != null) append("Duration: ${duration}s\n")
            append("Status: ${item.status}\n")
            if (latest != null) {
                append("Last: ${latest.lat}, ${latest.lng}\n")
                append("Speed: ${"%.2f".format(latest.speedMps)} m/s\n")
            }
        }.toString()

        val view = layoutInflater.inflate(R.layout.dialog_run_details, null)
        val summaryView = view.findViewById<android.widget.TextView>(R.id.textRunSummary)
        val copyButton = view.findViewById<android.widget.Button>(R.id.buttonCopyCsv)
        val replayButton = view.findViewById<android.widget.Button>(R.id.buttonReplayRun)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerEvents)

        summaryView.text = summary
        val eventAdapter = GpsEventAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = eventAdapter
        eventAdapter.submitList(events)

        copyButton.setOnClickListener {
            val csv = buildCsv(events)
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("gps_events", csv)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
        }

        replayButton.setOnClickListener {
            if (events.isEmpty()) return@setOnClickListener
            routePoints.clear()
            events.forEach { event ->
                routePoints.add(RoutePoint(event.lat, event.lng))
            }
            updateRouteLine()
            updatePointCount()
            binding.textError.text = ""
            binding.spinnerSpeedMode.setSelection(item.speedMode.coerceIn(0, 2))
            startRoutePlayback()
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_run_details))
            .setView(view)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun buildCsv(events: List<GpsEventItem>): String {
        val sb = StringBuilder()
        sb.append("timestamp,lat,lng,accuracy,speed_mps\n")
        events.forEach { event ->
            sb.append(event.timestamp).append(",")
                .append(event.lat).append(",")
                .append(event.lng).append(",")
                .append(event.accuracy).append(",")
                .append(String.format("%.2f", event.speedMps))
                .append("\n")
        }
        return sb.toString()
    }

    private fun showTeleportDialog(initialLatLng: org.maplibre.android.geometry.LatLng? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_teleport, null)
        val editCoords = view.findViewById<android.widget.EditText>(R.id.editCoords)
        
        if (initialLatLng != null) {
            editCoords.setText("%.6f, %.6f".format(initialLatLng.latitude, initialLatLng.longitude))
        }

        val textDistanceStatus = view.findViewById<android.widget.TextView>(R.id.textDistanceStatus)
        val btnFormat = view.findViewById<android.widget.Button>(R.id.btnFormat)
        val btnPaste = view.findViewById<android.widget.ImageButton>(R.id.btnPaste)
        val checkWalkMode = view.findViewById<android.widget.CheckBox>(R.id.checkWalkMode)
        val btnCancel = view.findViewById<android.widget.Button>(R.id.btnCancel)
        val btnTeleportAction = view.findViewById<android.widget.Button>(R.id.btnTeleportAction)

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        val updateHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val updateRunnable = object : Runnable {
            override fun run() {
                val raw = editCoords.text.toString()
                val parts = raw.split(",")
                if (parts.size >= 2) {
                    val targetLat = parts[0].trim().toDoubleOrNull()
                    val targetLng = parts[1].trim().toDoubleOrNull()
                    if (targetLat != null && targetLng != null) {
                        // 1. 優先使用模擬位置
                        var startLat = currentMockLat
                        var startLng = currentMockLng
                        
                        // 2. 如果沒在模擬，使用地圖顯示的當前位置 (Map Location Component)
                        if (startLat == 0.0) {
                            val mapLoc = mapLibre?.locationComponent?.lastKnownLocation
                            if (mapLoc != null) {
                                startLat = mapLoc.latitude
                                startLng = mapLoc.longitude
                            }
                        }
                        
                        // 3. 如果還是沒有，嘗試獲取真實位置 (保底)
                        if (startLat == 0.0) {
                            try {
                                val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                                val lastKnown = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                                             ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                                if (lastKnown != null) {
                                    startLat = lastKnown.latitude
                                    startLng = lastKnown.longitude
                                }
                            } catch (_: SecurityException) {}
                        }
                        
                        if (startLat != 0.0) {
                            val dist = calculateDistance(startLat, startLng, targetLat, targetLng)
                            val cooldown = calculateCooldown(dist)
                            textDistanceStatus.text = "Distance: %s - Cooldown: %ds".format(formatDistance(dist), cooldown)
                        } else {
                            textDistanceStatus.text = "Distance: Unknown - Click map first"
                        }
                    }
                }
                updateHandler.postDelayed(this, 1000)
            }
        }
        
        dialog.setOnShowListener { updateHandler.post(updateRunnable) }
        dialog.setOnDismissListener { updateHandler.removeCallbacks(updateRunnable) }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val item = clipboard.primaryClip?.getItemAt(0)
            val text = item?.text?.toString() ?: ""
            editCoords.setText(text)
        }

        btnFormat.setOnClickListener {
            val raw = editCoords.text.toString()
            val clean = raw.replace(Regex("[^0-9.,-]"), "")
            editCoords.setText(clean)
        }

        btnTeleportAction.setOnClickListener {
            val raw = editCoords.text.toString()
            val parts = raw.split(",")
            if (parts.size >= 2) {
                val lat = parts[0].trim().toDoubleOrNull()
                val lng = parts[1].trim().toDoubleOrNull()
                if (lat != null && lng != null) {
                    val intent = Intent(this@MainActivity, MockLocationService::class.java).apply {
                        action = MockLocationService.ACTION_TELEPORT
                        putExtra(MockLocationService.EXTRA_LAT, lat)
                        putExtra(MockLocationService.EXTRA_LNG, lng)
                        putExtra(MockLocationService.EXTRA_WALK_MODE, checkWalkMode.isChecked)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }

                    mapLibre?.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            org.maplibre.android.geometry.LatLng(lat, lng),
                            15.5
                        )
                    )
                }
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineLocationGranted || coarseLocationGranted) {
            mapLibre?.style?.let { style -> enableLocationComponent(style) }
            centerToCurrentLocation()
        } else {
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun centerToCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                mapLibre?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        org.maplibre.android.geometry.LatLng(it.latitude, it.longitude),
                        16.0
                    ),
                    1000
                )
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to get current location", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveRoute() {
        if (routePoints.size < 2) {
            Toast.makeText(this, R.string.error_route_points, Toast.LENGTH_SHORT).show()
            return
        }

        val input = android.widget.EditText(this)
        input.hint = getString(R.string.route_name_hint)

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.button_save_route)
            .setView(input)
            .setPositiveButton(R.string.button_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.error_route_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val entity = RouteEntity(
                    name = name,
                    pointsJson = RouteJson.toJson(routePoints),
                    createdAt = System.currentTimeMillis()
                )

                val db = AppDatabase.getInstance(this)
                lifecycleScope.launch {
                    db.routeDao().insert(entity)
                    binding.layoutRouteEditor.visibility = android.view.View.GONE
                    Toast.makeText(this@MainActivity, "Route saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun renameSelectedRoute() {
        val id = selectedRouteId ?: run {
            binding.textError.setText(R.string.error_route_select)
            return
        }
        binding.textError.text = ""
    }

    private fun addFavorite() {
        val name = binding.editFavoriteName.text.toString().trim()
        if (name.isEmpty()) {
            binding.textError.setText(R.string.error_favorite_name)
            return
        }
        val point = lastTappedPoint ?: run {
            binding.textError.setText(R.string.error_favorite_point)
            return
        }
        val entity = FavoriteEntity(
            name = name,
            lat = point.latitude,
            lng = point.longitude,
            note = null,
            createdAt = System.currentTimeMillis()
        )
        val db = AppDatabase.getInstance(this)
        lifecycleScope.launch {
            db.favoriteDao().insert(entity)
        }
        binding.editFavoriteName.text?.clear()
        binding.textError.text = ""
    }

    private fun showFavoriteEditDialog(item: FavoriteItem) {
        val view = layoutInflater.inflate(R.layout.dialog_favorite_edit, null)
        val nameField = view.findViewById<android.widget.EditText>(R.id.editFavoriteNameDialog)
        val noteField = view.findViewById<android.widget.EditText>(R.id.editFavoriteNoteDialog)
        val addToRouteButton = view.findViewById<android.widget.Button>(R.id.buttonAddToRouteDialog)
        nameField.setText(item.name)
        noteField.setText(item.note ?: "")
        addToRouteButton.setOnClickListener {
            addFavoriteToRoute(item)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_edit_favorite))
            .setView(view)
            .setPositiveButton(getString(R.string.button_save)) { _, _ ->
                val newName = nameField.text.toString().trim()
                if (newName.isEmpty()) {
                    binding.textError.setText(R.string.error_favorite_name)
                    return@setPositiveButton
                }
                val newNote = noteField.text.toString().trim().ifEmpty { null }
                val db = AppDatabase.getInstance(this)
                lifecycleScope.launch {
                    db.favoriteDao().update(item.id, newName, newNote)
                }
            }
            .setNeutralButton(getString(R.string.button_use_as_start)) { _, _ ->
                useFavoriteAsStart(item)
            }
            .setNegativeButton(getString(R.string.button_cancel), null)
            .show()
    }

    private fun useFavoriteAsStart(item: FavoriteItem) {
        routePoints.clear()
        routePoints.add(RoutePoint(item.lat, item.lng))
        updateRouteLine()
        updatePointCount()
        selectedRouteId = null
        mapLibre?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                org.maplibre.android.geometry.LatLng(item.lat, item.lng),
                15.5
            )
        )
    }

    private fun addFavoriteToRoute(item: FavoriteItem) {
        routePoints.add(RoutePoint(item.lat, item.lng))
        updateRouteLine()
        updatePointCount()
        mapLibre?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                org.maplibre.android.geometry.LatLng(item.lat, item.lng),
                15.5
            )
        )
    }

    private fun removeNearestPoint(lat: Double, lng: Double) {
        if (routePoints.isEmpty()) return
        val thresholdMeters = 40.0
        var nearestIndex = -1
        var nearestDistance = Double.MAX_VALUE
        for (i in routePoints.indices) {
            val point = routePoints[i]
            val distance = haversineMeters(lat, lng, point.latitude, point.longitude)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestIndex = i
            }
        }
        if (nearestIndex >= 0 && nearestDistance <= thresholdMeters) {
            routePoints.removeAt(nearestIndex)
            updateRouteLine()
            updatePointCount()
        }
    }

    private fun tryStartDrag(lat: Double, lng: Double): Boolean {
        if (routePoints.isEmpty()) return false
        val thresholdMeters = 40.0
        var nearestIndex = -1
        var nearestDistance = Double.MAX_VALUE
        for (i in routePoints.indices) {
            val point = routePoints[i]
            val distance = haversineMeters(lat, lng, point.latitude, point.longitude)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestIndex = i
            }
        }
        if (nearestIndex >= 0 && nearestDistance <= thresholdMeters) {
            isDraggingPoint = true
            draggingIndex = nearestIndex
            mapLibre?.uiSettings?.setAllGesturesEnabled(false)
            return true
        }
        return false
    }

    private fun stopDrag() {
        isDraggingPoint = false
        draggingIndex = -1
        mapLibre?.uiSettings?.setAllGesturesEnabled(true)
        updatePointCount()
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private fun updatePointCount() {
        binding.textPointCount.text = getString(R.string.label_point_count, routePoints.size)
        updateRouteStats()
    }

    private fun updateRouteStats() {
        if (routePoints.size < 2) {
            binding.textRouteStats.text = "📍 ${routePoints.size} 點 | 📏 0 m"
            return
        }
        var totalDist = 0.0
        for (i in 0 until routePoints.size - 1) {
            totalDist += haversineMeters(
                routePoints[i].latitude, routePoints[i].longitude,
                routePoints[i + 1].latitude, routePoints[i + 1].longitude
            )
        }
        val speedMode = binding.spinnerSpeedMode.selectedItemPosition
        val speedMps = when (speedMode) {
            1 -> 2.8   // Jog
            2 -> 13.9  // Drive
            else -> 1.4 // Walk
        }
        val speedKmh = speedMps * 3.6
        val estSeconds = totalDist / speedMps
        val distStr = if (totalDist >= 1000) "%.2f km".format(totalDist / 1000.0) else "%.0f m".format(totalDist)
        val timeStr = formatDuration((estSeconds * 1000).toLong())
        binding.textRouteStats.text = "📍 ${routePoints.size} 點 | 📏 $distStr | ⏱ ~$timeStr | %.1f km/h".format(speedKmh)
    }

    private fun ensurePermissionsAndStart() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
            return
        }

        if (needsNotificationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
            return
        }

        if (!isMockLocationEnabled()) {
            showMockLocationSetupDialog()
            return
        }

        startRoutePlayback()
    }

    @Suppress("DEPRECATION")
    private fun isMockLocationEnabled(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager ?: return false
        return try {
            val mode = appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_MOCK_LOCATION,
                android.os.Process.myUid(),
                packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun showMockLocationSetupDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("需要設定模擬定位")
            .setMessage("請先到「開發人員選項」中，將本 APP 設定為「模擬定位應用程式」。\n\n路徑：設定 → 開發人員選項 → 選取模擬位置應用程式 → 選擇 SofaWander")
            .setPositiveButton("前往設定") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startRoutePlayback() {
        if (routePoints.size < 2) {
            Toast.makeText(this, R.string.error_route_points, Toast.LENGTH_SHORT).show()
            return
        }
        val speedMode = binding.spinnerSpeedMode.selectedItemPosition
        val speedMinText = binding.editSpeedMin.text.toString().trim()
        val speedMaxText = binding.editSpeedMax.text.toString().trim()
        val pauseMinText = binding.editPauseMin.text.toString().trim()
        val pauseMaxText = binding.editPauseMax.text.toString().trim()
        val randomSpeed = binding.checkRandomSpeed.isChecked
        val loopEnabled = binding.checkLoop.isChecked
        val roundTripEnabled = binding.checkRoundTrip.isChecked
        val driftEnabled = binding.checkDrift.isChecked
        val bounceEnabled = binding.checkBounce.isChecked
        val smoothingEnabled = binding.checkSmoothing.isChecked

        val driftMeters = binding.editDriftMeters.text.toString().toDoubleOrNull() ?: 0.0
        val bounceMeters = binding.editBounceMeters.text.toString().toDoubleOrNull() ?: 0.0
        val smoothingAlpha = binding.editSmoothingAlpha.text.toString().toDoubleOrNull() ?: 0.0

        if (!validateRanges(showError = true)) {
            Toast.makeText(this, "Speed/Pause range invalid", Toast.LENGTH_SHORT).show()
            return
        }

        if (driftEnabled && !isInRange(driftMeters, 0.0, MAX_DRIFT_METERS)) {
            binding.textStatus.setText(R.string.status_error)
            binding.textError.setText(R.string.error_drift_range)
            binding.editDriftMeters.error = getString(R.string.error_drift_range)
            return
        }
        if (bounceEnabled && !isInRange(bounceMeters, 0.0, MAX_BOUNCE_METERS)) {
            binding.textStatus.setText(R.string.status_error)
            binding.textError.setText(R.string.error_bounce_range)
            binding.editBounceMeters.error = getString(R.string.error_bounce_range)
            return
        }
        if (smoothingEnabled && !isInRange(smoothingAlpha, 0.0, MAX_SMOOTHING_ALPHA)) {
            binding.textStatus.setText(R.string.status_error)
            binding.textError.setText(R.string.error_smoothing_range)
            binding.editSmoothingAlpha.error = getString(R.string.error_smoothing_range)
            return
        }

        val speedRange = normalizeRange(speedMinText, speedMaxText, MAX_SPEED_KMH)
        if (speedRange == null) {
            Toast.makeText(this, R.string.error_speed_range, Toast.LENGTH_SHORT).show()
            return
        }

        val pauseRange = normalizeRange(pauseMinText, pauseMaxText, MAX_PAUSE_SEC)
        if (pauseRange == null) {
            Toast.makeText(this, R.string.error_pause_range, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_ROUTE
            putExtra(MockLocationService.EXTRA_ROUTE_JSON, RouteJson.toJson(routePoints))
            putExtra(MockLocationService.EXTRA_SPEED_MODE, speedMode)
            putExtra(MockLocationService.EXTRA_SPEED_MIN_KMH, speedRange.first)
            putExtra(MockLocationService.EXTRA_SPEED_MAX_KMH, speedRange.second)
            putExtra(MockLocationService.EXTRA_PAUSE_MIN_SEC, pauseRange.first)
            putExtra(MockLocationService.EXTRA_PAUSE_MAX_SEC, pauseRange.second)
            putExtra(MockLocationService.EXTRA_RANDOM_SPEED, randomSpeed)
            putExtra(MockLocationService.EXTRA_LOOP_ENABLED, loopEnabled)
            putExtra(MockLocationService.EXTRA_ROUNDTRIP_ENABLED, roundTripEnabled)
            putExtra(MockLocationService.EXTRA_DRIFT_ENABLED, driftEnabled)
            putExtra(MockLocationService.EXTRA_BOUNCE_ENABLED, bounceEnabled)
            putExtra(MockLocationService.EXTRA_SMOOTHING_ENABLED, smoothingEnabled)
            putExtra(MockLocationService.EXTRA_DRIFT_METERS, driftMeters)
            putExtra(MockLocationService.EXTRA_BOUNCE_METERS, bounceMeters)
            putExtra(MockLocationService.EXTRA_SMOOTHING_ALPHA, smoothingAlpha)
        }
        ContextCompat.startForegroundService(this, intent)
        binding.textStatus.setText(R.string.status_running)
        binding.textError.text = ""
    }

    private fun normalizeRange(minText: String, maxText: String, maxAllowed: Double? = null): Pair<Double, Double>? {
        if (minText.isEmpty() && maxText.isEmpty()) return 0.0 to 0.0
        val min = if (minText.isEmpty()) null else minText.toDoubleOrNull()
        val max = if (maxText.isEmpty()) null else maxText.toDoubleOrNull()
        if (min == null && max == null) return null
        val safeMin = min ?: max ?: return null
        val safeMax = max ?: min ?: return null
        if (safeMin < 0.0 || safeMax < 0.0) return null
        if (safeMax < safeMin) return null
        if (maxAllowed != null && (safeMin > maxAllowed || safeMax > maxAllowed)) return null
        return safeMin to safeMax
    }

    private fun applySpeedDefaultsIfEmpty(mode: Int) {
        val minText = binding.editSpeedMin.text.toString().trim()
        val maxText = binding.editSpeedMax.text.toString().trim()
        if (minText.isNotEmpty() || maxText.isNotEmpty()) return
        val (min, max) = when (mode) {
            1 -> 6.0 to 10.0   // Jog
            2 -> 30.0 to 60.0  // Drive
            else -> 3.0 to 5.0 // Walk
        }
        binding.editSpeedMin.setText(min.toString())
        binding.editSpeedMax.setText(max.toString())
    }

    private fun applySpeedPreset(mode: Int) {
        val (min, max) = when (mode) {
            1 -> 6.0 to 10.0   // Jog
            2 -> 30.0 to 60.0  // Drive
            else -> 3.0 to 5.0 // Walk
        }
        binding.editSpeedMin.setText(min.toString())
        binding.editSpeedMax.setText(max.toString())
    }

    private fun applyPausePreset(mode: Int) {
        val (min, max) = when (mode) {
            1 -> 2.0 to 5.0   // Short
            2 -> 8.0 to 15.0  // Long
            else -> 0.0 to 0.0 // No pause
        }
        binding.editPauseMin.setText(min.toString())
        binding.editPauseMax.setText(max.toString())
    }

    private fun setupInputFilters() {
        val filter = DecimalInputFilter()
        binding.editSpeedMin.filters = arrayOf(filter)
        binding.editSpeedMax.filters = arrayOf(filter)
        binding.editPauseMin.filters = arrayOf(filter)
        binding.editPauseMax.filters = arrayOf(filter)
        binding.editDriftMeters.filters = arrayOf(filter)
        binding.editBounceMeters.filters = arrayOf(filter)
        binding.editSmoothingAlpha.filters = arrayOf(filter)
    }

    private fun setupTooltips() {
        ViewCompat.setTooltipText(binding.iconSpeedInfo, getString(R.string.tooltip_speed_range))
        ViewCompat.setTooltipText(binding.iconPauseInfo, getString(R.string.tooltip_pause_range))
    }

    private fun initM4Defaults() {
        binding.checkDrift.isChecked = false
        binding.checkBounce.isChecked = false
        binding.checkSmoothing.isChecked = false
        binding.editDriftMeters.isEnabled = false
        binding.editBounceMeters.isEnabled = false
        binding.editSmoothingAlpha.isEnabled = false
        binding.editDriftMeters.setText("3")
        binding.editBounceMeters.setText("5")
        binding.editSmoothingAlpha.setText("0.3")
    }

    private fun attachRangeWatchers() {
        val watcher = SimpleTextWatcher {
            validateRanges(showError = false)
        }
        binding.editSpeedMin.addTextChangedListener(watcher)
        binding.editSpeedMax.addTextChangedListener(watcher)
        binding.editPauseMin.addTextChangedListener(watcher)
        binding.editPauseMax.addTextChangedListener(watcher)
    }

    private fun validateRanges(showError: Boolean): Boolean {
        val speedRange = normalizeRange(
            binding.editSpeedMin.text.toString().trim(),
            binding.editSpeedMax.text.toString().trim(),
            MAX_SPEED_KMH
        )
        if (speedRange == null) {
            if (showError) {
                binding.textStatus.setText(R.string.status_error)
                binding.textError.setText(R.string.error_speed_range)
                setRangeError(binding.editSpeedMin, binding.editSpeedMax, true, R.string.error_speed_range)
            }
            return false
        } else {
            setRangeError(binding.editSpeedMin, binding.editSpeedMax, false, R.string.error_speed_range)
        }

        val pauseRange = normalizeRange(
            binding.editPauseMin.text.toString().trim(),
            binding.editPauseMax.text.toString().trim(),
            MAX_PAUSE_SEC
        )
        if (pauseRange == null) {
            if (showError) {
                binding.textStatus.setText(R.string.status_error)
                binding.textError.setText(R.string.error_pause_range)
                setRangeError(binding.editPauseMin, binding.editPauseMax, true, R.string.error_pause_range)
            }
            return false
        } else {
            setRangeError(binding.editPauseMin, binding.editPauseMax, false, R.string.error_pause_range)
        }

        if (!showError) {
            binding.textError.text = ""
        }
        return true
    }

    private fun isInRange(value: Double, min: Double, max: Double): Boolean {
        return value >= min && value <= max
    }

    private fun setRangeError(
        minField: EditText,
        maxField: EditText,
        hasError: Boolean,
        messageRes: Int
    ) {
        if (hasError) {
            val message = getString(messageRes)
            minField.error = message
            maxField.error = message
        } else {
            minField.error = null
            maxField.error = null
        }
    }

    private class DecimalInputFilter : InputFilter {
        private val pattern = Regex("^\\d*(\\.\\d{0,2})?$")

        override fun filter(
            source: CharSequence,
            start: Int,
            end: Int,
            dest: Spanned,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            val newValue = StringBuilder(dest)
                .replace(dstart, dend, source.subSequence(start, end).toString())
                .toString()
            return if (pattern.matches(newValue)) null else ""
        }
    }

    private class SimpleTextWatcher(private val onChange: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = onChange()
    }

    private fun pauseRoutePlayback() {
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_PAUSE_ROUTE
        }
        startService(intent)
    }

    private fun stopRoutePlayback() {
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP_ROUTE
        }
        startService(intent)
        stopService(Intent(this, MockLocationService::class.java))
        binding.textStatus.setText(R.string.status_idle)
        binding.textError.text = ""
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        ContextCompat.registerReceiver(
            this,
            mockStatusReceiver,
            IntentFilter(MockLocationService.ACTION_MOCK_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            mockProgressReceiver,
            IntentFilter(MockLocationService.ACTION_MOCK_PROGRESS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            mockRouteReceiver,
            IntentFilter(MockLocationService.ACTION_ROUTE_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        syncStatusFromPrefs()
        if (!isLocationEnabled()) {
            binding.textStatus.setText(R.string.status_error)
            binding.textError.setText(R.string.error_location_disabled)
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
        try {
            unregisterReceiver(mockStatusReceiver)
            unregisterReceiver(mockProgressReceiver)
            unregisterReceiver(mockRouteReceiver)
        } catch (e: Exception) {
            android.util.Log.e("MockApp", "Error unregistering receivers", e)
        }
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION && grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ensurePermissionsAndStart()
            } else {
                binding.textStatus.setText(R.string.status_error)
                binding.textError.setText(R.string.error_no_permission)
            }
        }

        if (requestCode == REQUEST_NOTIFICATION_PERMISSION && grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRoutePlayback()
            } else {
                binding.textStatus.setText(R.string.status_error)
                binding.textError.setText(R.string.error_notifications)
            }
        }
    }

    private fun needsNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
    }

    private fun syncStatusFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isRunning = prefs.getBoolean(PREF_KEY_RUNNING, false)
        binding.textStatus.setText(if (isRunning) R.string.status_running else R.string.status_idle)
        
        // 如果正在執行，則恢復顯示統計條
        if (isRunning) {
            binding.layoutPlaybackStats.visibility = View.VISIBLE
            binding.buttonStartRoute.text = "Stop Route"
            isRouteRunning = true
        } else {
            binding.layoutPlaybackStats.visibility = View.GONE
            binding.buttonStartRoute.text = "Start Route"
            isRouteRunning = false
        }
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 1001
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
        private const val PREFS_NAME = "mock_prefs"
        private const val PREF_KEY_RUNNING = "mock_service_running"
        private const val MAX_SPEED_KMH = 200.0
        private const val MAX_PAUSE_SEC = 120.0
        private const val MAX_DRIFT_METERS = 50.0
        private const val MAX_BOUNCE_METERS = 50.0
        private const val MAX_SMOOTHING_ALPHA = 1.0
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private const val MAP_STYLE_URL = "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json"
        private const val SOURCE_ID_SELECTED = "selected-point-source"
        private const val LAYER_ID_SELECTED = "selected-point-layer"
        private const val ICON_ID_SELECTED = "selected-point-icon"
        
        private const val ROUTE_SOURCE_ID = "route-source"
        private const val ROUTE_LAYER_ID = "route-layer"
        private const val ROUTE_ARROW_LAYER_ID = "route-arrow-layer"
        private const val POINTS_SOURCE_ID = "points-source"
        private const val POINTS_LAYER_ID = "points-layer"

        private const val MAP_ROUTE_COLOR = "#21f380"
        private const val MAP_POINT_COLOR = "#FF5722"
        private const val MAP_POINT_STROKE = "#FFFFFF"
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private fun calculateCooldown(meters: Double): Int {
        val km = meters / 1000.0
        return when {
            km < 0.25 -> 0
            km < 0.5 -> 30
            km < 1.0 -> 60
            km < 5.0 -> 120
            km < 10.0 -> 420
            km < 25.0 -> 660
            km < 50.0 -> 1200
            km < 100.0 -> 2100
            km < 250.0 -> 2700
            km < 500.0 -> 3600
            km < 1000.0 -> 4800
            else -> 7200
        }
    }

    private fun performSearch() {
        val query = binding.editSearch.text.toString().trim()
        if (query.isEmpty()) return

        hideKeyboard()

        // 1. 嘗試解析是否為座標 "lat, lng"
        val parts = query.split(",")
        if (parts.size >= 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lng = parts[1].trim().toDoubleOrNull()
            if (lat != null && lng != null) {
                val point = org.maplibre.android.geometry.LatLng(lat, lng)
                mapLibre?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(point, 15.0))
                updateSelectedPointSource(point)
                showTeleportDialog(point)
                return
            }
        }

        // 2. 使用 Geocoder 搜尋地址
        if (!Geocoder.isPresent()) {
            Toast.makeText(this, "Geocoder not available", Toast.LENGTH_SHORT).show()
            return
        }

        val geocoder = Geocoder(this, Locale.getDefault())
        try {
            // 注意：API 33+ 有新的 getFromLocationName，這裡用舊的相容版或簡易版
            val addresses = geocoder.getFromLocationName(query, 1)
            if (addresses != null && addresses.isNotEmpty()) {
                val addr = addresses[0]
                val point = org.maplibre.android.geometry.LatLng(addr.latitude, addr.longitude)
                mapLibre?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(point, 15.0))
                updateSelectedPointSource(point)
                showTeleportDialog(point)
            } else {
                Toast.makeText(this, "找不到該地點", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("MockApp", "Search error", e)
            Toast.makeText(this, "搜尋出錯: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
}
