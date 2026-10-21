package com.vipin.fitness

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vipin.fitness.core.designsystem.FitnessTheme
import androidx.activity.viewModels
import androidx.compose.material3.Surface
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
                    FitnessNavDisplay(startRoute = appViewModel.startRoute, sessionExpired = appViewModel.sessionExpired)
                }
            }
        }
    }
}
