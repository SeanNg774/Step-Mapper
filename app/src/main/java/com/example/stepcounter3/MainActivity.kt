package com.example.stepcounter3

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.stepcounter3.View.MapScreen
import com.example.stepcounter3.View.StepCounterScreen
import com.google.maps.android.compose.MapType
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {

    private val trailState = mutableStateOf<List<TrailPoint>>(emptyList())
    private var stepService: StepService? = null
    private var isBound = false
    private lateinit var stepCounterViewModel: StepCounterViewModel

    private val connection = object : ServiceConnection {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as StepService.LocalBinder
            stepService = binder.getService()
            isBound = true

            // Push sensor data directly into the ViewModel!
            lifecycleScope.launch {
                stepService?.totalSteps?.collect { steps ->
                    if (::stepCounterViewModel.isInitialized) {
                        stepCounterViewModel.onStepsReceived(steps)
                    }
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, StepService::class.java).also { intent ->
            bindService(intent, connection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load visual memory
        val shared = getSharedPreferences("myPrefs", MODE_PRIVATE)
        val savedTrailString = shared.getString("savedTrail", "") ?: ""
        val initialTrail = stringToTrail(savedTrailString)
        val savedTrailSteps = shared.getInt("savedTrailSteps", 0)
        val savedTrailDuration = shared.getLong("savedTrailDuration", 0L)
        val savedStrideLength = shared.getFloat("strideLength", 0.7f).toDouble()
        val defaultLat = shared.getFloat("defaultLat", 2.9278f).toDouble()
        val defaultLon = shared.getFloat("defaultLon", 101.6419f).toDouble()

        // Permissions
        val permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                val context = LocalContext.current
                val routePrefs = context.getSharedPreferences("RouteSettings", Context.MODE_PRIVATE)

                val viewModelFactory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return StepCounterViewModel(routePrefs) as T
                    }
                }

                stepCounterViewModel = viewModel(factory = viewModelFactory)

                // Jump-start the Service if the ViewModel remembers it was running!
                if (stepCounterViewModel.isSessionRunning) {
                    val restoreIntent = Intent(this@MainActivity, StepService::class.java)
                    startForegroundService(restoreIntent)
                }

                NavHost(navController, startDestination = "stepCounter") {
                    composable("stepCounter") {
                        StepCounterScreen(
                            viewModel = stepCounterViewModel,
                            initialStrideLength = savedStrideLength,
                            initialDuration = savedTrailDuration,
                            initialTrail = initialTrail,
                            initialSteps = savedTrailSteps,
                            defaultLat = defaultLat,
                            defaultLon = defaultLon,
                            onSaveStartLocation = { lat, lon ->
                                shared.edit {
                                    putFloat("defaultLat", lat.toFloat())
                                    putFloat("defaultLon", lon.toFloat())
                                }
                            },
                            onTrailUpdated = { updatedTrail, currentSteps, currentDuration ->
                                trailState.value = updatedTrail
                                saveTrailToPrefs(context,updatedTrail, currentSteps, currentDuration)
                            },
                            onStrideLengthChanged = { newLength ->
                                shared.edit { putFloat("strideLength", newLength.toFloat()) }
                            },
                            onClearSavedData = {
                                shared.edit {
                                    putString("savedTrail", "")
                                    putInt("savedTrailSteps", 0)
                                }
                            },
                            onSyncStepBaseline = { extraSteps ->
                                val newBaseline =
                                    stepCounterViewModel.sessionStartSteps + extraSteps
                                stepCounterViewModel.sessionStartSteps = newBaseline
                            }
                        )
                    }

                }
            }
        }
    }

}