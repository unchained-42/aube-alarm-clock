package com.reveil.aube

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.reveil.aube.onboarding.OnboardingQrCodeScreen
import com.reveil.aube.onboarding.OnboardingReliabilityScreen
import com.reveil.aube.onboarding.OnboardingWakeTimeScreen
import com.reveil.aube.onboarding.OnboardingWelcomeScreen
import com.reveil.aube.settings.LanguageSettingsScreen
import com.reveil.aube.settings.LocaleHelper
import com.reveil.aube.settings.PermissionsScreen
import com.reveil.aube.settings.QrSetupScreen
import com.reveil.aube.settings.RoutineSettingsScreen
import com.reveil.aube.settings.SettingsRepository
import com.reveil.aube.settings.SettingsScreen
import com.reveil.aube.ui.HomeScreen
import com.reveil.aube.ui.WhyStrictScreen
import com.reveil.aube.ui.theme.AubeTheme
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsRepository = SettingsRepository(applicationContext)

        setContent {
            AubeTheme {
                val notificationPermissionLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

                // Decided once per cold start: first-ever launch goes to onboarding, every
                // launch after that goes straight to Home — never re-ask.
                var rootRoute by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    val completed = settingsRepository.settings.first().onboardingCompleted
                    rootRoute = if (completed) "home" else "onboarding_welcome"
                }

                val route = rootRoute
                if (route == null) {
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                } else {
                    val navController = rememberNavController()

                    LaunchedEffect(route) {
                        // Guards against Navigation Compose restoring a stale mid-flow screen
                        // (e.g. "qr_setup") after the process was killed and recreated — an
                        // alarm app should always resume at a known root, not wherever the
                        // back stack happened to be.
                        if (navController.currentDestination?.route != route) {
                            navController.navigate(route) {
                                popUpTo(navController.graph.id) { inclusive = true }
                            }
                        }
                    }

                    NavHost(navController = navController, startDestination = route) {
                        composable("onboarding_welcome") {
                            OnboardingWelcomeScreen(onContinue = { navController.navigate("onboarding_wake_time") })
                        }
                        composable("onboarding_wake_time") {
                            OnboardingWakeTimeScreen(
                                settingsRepository = settingsRepository,
                                onContinue = { navController.navigate("onboarding_qr_code") }
                            )
                        }
                        composable("onboarding_qr_code") {
                            OnboardingQrCodeScreen(
                                onContinue = { navController.navigate("onboarding_reliability") }
                            )
                        }
                        composable("onboarding_reliability") {
                            OnboardingReliabilityScreen(
                                settingsRepository = settingsRepository,
                                onFinished = {
                                    navController.navigate("home") {
                                        popUpTo(navController.graph.id) { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("home") {
                            HomeScreen(
                                settingsRepository = settingsRepository,
                                onOpenSettings = { navController.navigate("settings") },
                                onOpenWhyStrict = { navController.navigate("why_strict") }
                            )
                        }
                        composable("why_strict") {
                            WhyStrictScreen(onBack = { navController.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(
                                settingsRepository = settingsRepository,
                                onBack = { navController.popBackStack() },
                                onOpenRoutineSettings = { navController.navigate("routine_settings") },
                                onOpenQrSetup = { navController.navigate("qr_setup") },
                                onOpenLanguageSettings = { navController.navigate("language_settings") },
                                onOpenPermissions = { navController.navigate("permissions") }
                            )
                        }
                        composable("routine_settings") {
                            RoutineSettingsScreen(settingsRepository = settingsRepository, onBack = { navController.popBackStack() })
                        }
                        composable("qr_setup") {
                            QrSetupScreen(settingsRepository = settingsRepository, onBack = { navController.popBackStack() })
                        }
                        composable("language_settings") {
                            LanguageSettingsScreen(onBack = { navController.popBackStack() })
                        }
                        composable("permissions") {
                            PermissionsScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
