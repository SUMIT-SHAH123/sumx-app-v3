package com.sumx.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sumx.app.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            SumxTheme {
                val vm: AuthViewModel = viewModel()
                when (vm.screen) {
                    Screen.Splash -> Box(Modifier.fillMaxSize().background(SumxColors.Bg), Alignment.Center) { CircularProgressIndicator(color = SumxColors.Cyan) }
                    Screen.Login -> LoginScreen(vm)
                    Screen.Register -> RegisterScreen(vm)
                    Screen.Verify -> VerifyScreen(vm)
                    Screen.Forgot -> ForgotScreen(vm)
                    Screen.Reset -> ResetScreen(vm)
                    Screen.Home -> AppShell(vm)
                }
            }
        }
    }
}
