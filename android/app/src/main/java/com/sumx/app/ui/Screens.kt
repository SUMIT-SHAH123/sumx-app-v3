package com.sumx.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

@Composable
fun LoginScreen(vm: AuthViewModel) {
    val ctx = LocalContext.current
    var id by remember { mutableStateOf("") }; var pw by remember { mutableStateOf("") }; var remember by remember { mutableStateOf(true) }
    AuthScaffold("Welcome back", "Log in with your email, username or SUM X ID.", vm) {
        Field(id, { id = it }, "Email, username or @SUM X ID", keyboard = KeyboardType.Email)
        Field(pw, { pw = it }, "Password", password = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(remember, { remember = it }); Text("Remember me", fontSize = 14.sp); Spacer(Modifier.weight(1f))
            LinkText("Forgot password?") { vm.go(Screen.Forgot) }
        }
        PrimaryButton("Log in", vm.busy) { vm.login(id, pw, remember) }
        GoogleButton(vm.busy) { vm.google(ctx) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("New to SUM X?", color = SumxColors.Muted); LinkText("Create account") { vm.go(Screen.Register) }
        }
    }
}

@Composable
fun RegisterScreen(vm: AuthViewModel) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }; var uname by remember { mutableStateOf("") }; var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }; var pw2 by remember { mutableStateOf("") }; var dob by remember { mutableStateOf("") }
    var terms by remember { mutableStateOf(false) }
    AuthScaffold("Create your account", "Free for everyone. No subscriptions, ever.", vm) {
        Field(name, { name = it }, "Display name")
        Field(uname, { uname = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(20) }, "Username",
            hint = "Your SUM X ID will be @${uname.ifBlank { "username" }}1234 (digits are assigned for you)")
        Field(email, { email = it }, "Email", keyboard = KeyboardType.Email)
        Field(pw, { pw = it }, "Password (8+ characters)", password = true)
        Field(pw2, { pw2 = it }, "Confirm password", password = true)
        OutlinedButton({
            val c = Calendar.getInstance(); c.add(Calendar.YEAR, -18)
            DatePickerDialog(ctx, { _, y, m, d -> dob = "%04d-%02d-%02d".format(y, m + 1, d) }, c[Calendar.YEAR], c[Calendar.MONTH], c[Calendar.DAY_OF_MONTH])
                .apply { datePicker.maxDate = System.currentTimeMillis() }.show()
        }, Modifier.fillMaxWidth().height(50.dp)) { Text(if (dob.isBlank()) "Date of birth" else "Date of birth: $dob", color = SumxColors.Text) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(terms, { terms = it }); Text("I accept the Terms of Service and Privacy Policy (you must be 13+).", fontSize = 13.sp)
        }
        PrimaryButton("Create account", vm.busy) { vm.register(name, uname, email, pw, pw2, dob, terms) }
        GoogleButton(vm.busy) { vm.google(ctx) }
        LinkText("I already have an account") { vm.go(Screen.Login) }
    }
}

@Composable
fun VerifyScreen(vm: AuthViewModel) {
    var code by remember { mutableStateOf("") }
    AuthScaffold("Verify your email", "Enter the 6-digit code sent to ${vm.pendingEmail.ifBlank { "your email" }}.", vm) {
        Field(code, { code = it.filter(Char::isDigit).take(6) }, "6-digit code", keyboard = KeyboardType.NumberPassword)
        PrimaryButton("Verify", vm.busy) { vm.verify(code) }
        LinkText("Resend code") { vm.resend() }
        LinkText("Back to login") { vm.go(Screen.Login) }
    }
}

@Composable
fun ForgotScreen(vm: AuthViewModel) {
    var email by remember { mutableStateOf("") }
    AuthScaffold("Forgot password", "We'll email you a code to reset it.", vm) {
        Field(email, { email = it }, "Email", keyboard = KeyboardType.Email)
        PrimaryButton("Send reset code", vm.busy) { vm.forgot(email) }
        LinkText("Back to login") { vm.go(Screen.Login) }
    }
}

@Composable
fun ResetScreen(vm: AuthViewModel) {
    var code by remember { mutableStateOf("") }; var pw by remember { mutableStateOf("") }
    AuthScaffold("Reset password", "Enter the code from your email and a new password.", vm) {
        Field(code, { code = it.filter(Char::isDigit).take(6) }, "6-digit code", keyboard = KeyboardType.NumberPassword)
        Field(pw, { pw = it }, "New password (8+ characters)", password = true)
        PrimaryButton("Update password", vm.busy) { vm.reset(code, pw) }
        LinkText("Back to login") { vm.go(Screen.Login) }
    }
}
