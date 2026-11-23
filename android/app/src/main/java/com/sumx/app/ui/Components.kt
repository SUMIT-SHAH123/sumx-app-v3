package com.sumx.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AuthScaffold(title: String, subtitle: String, vm: AuthViewModel, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(SumxColors.Bg, SumxColors.Surface)))) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                Text("SUM X", fontSize = 34.sp, fontWeight = FontWeight.Black, color = SumxColors.Text, letterSpacing = 2.sp)
                Box(Modifier.padding(top = 4.dp).width(44.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                    .background(Brush.horizontalGradient(listOf(SumxColors.Blue, SumxColors.Cyan))))
                Spacer(Modifier.height(24.dp))
                Surface(color = SumxColors.Card.copy(alpha = 0.92f), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, SumxColors.Border)) {
                    Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(subtitle, color = SumxColors.Muted, fontSize = 14.sp)
                        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
                        vm.info?.let { Text(it, color = SumxColors.Cyan, fontSize = 14.sp) }
                        content()
                    }
                }
            }
        }
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, hint: String? = null) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        supportingText = hint?.let { { Text(it) } },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SumxColors.Cyan, unfocusedBorderColor = SumxColors.Border,
            focusedLabelColor = SumxColors.Cyan, cursorColor = SumxColors.Cyan, unfocusedLabelColor = SumxColors.Muted)
    )
}

@Composable
fun PrimaryButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().height(50.dp), enabled = !busy, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SumxColors.Blue)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = SumxColors.Text) else Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GoogleButton(busy: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.fillMaxWidth().height(50.dp), enabled = !busy, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SumxColors.Border)) {
        Text("G", fontWeight = FontWeight.Black, color = SumxColors.Cyan); Spacer(Modifier.width(10.dp))
        Text("Continue with Google", color = SumxColors.Text)
    }
}

@Composable
fun LinkText(text: String, onClick: () -> Unit) = TextButton(onClick) { Text(text, color = SumxColors.Cyan) }
