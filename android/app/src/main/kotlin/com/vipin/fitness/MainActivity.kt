package com.vipin.fitness

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.designsystem.FitnessTheme
import com.vipin.fitness.navigation.FitnessNavDisplay
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FitnessTheme {
                Surface {
                    val start by appViewModel.startRoute.collectAsStateWithLifecycle()
                    start?.let {
                        FitnessNavDisplay(
                            startRoute = it,
                            isSignedIn = appViewModel::isSignedIn,
                            sessionExpired = appViewModel.sessionExpired,
                        )
                    }
                }
            }
        }
    }
}
