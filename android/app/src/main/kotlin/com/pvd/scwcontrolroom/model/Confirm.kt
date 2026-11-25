package com.pvd.scwcontrolroom.model

import android.content.Context
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The app's own small settings, as the iPhone app keeps them in UserDefaults. */
fun appPrefs(context: Context) = context.getSharedPreferences("app", Context.MODE_PRIVATE)

/** Whether stop and reboot first ask for a fingerprint, face or the screen lock. Off by default. */
const val AUTH_BEFORE_ACTIONS = "authBeforeActions"

sealed interface Confirmation {
    data object Confirmed : Confirmation
    data object Cancelled : Confirmation
    data class Failed(val message: String) : Confirmation
}

/**
 * Asks the phone's owner to confirm with a fingerprint, their face or the
 * screen lock, as the iPhone app asks for Face ID or the passcode.
 */
suspend fun confirmOwner(activity: FragmentActivity, title: String): Confirmation =
    suspendCancellableCoroutine { continuation ->
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(Confirmation.Confirmed)
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    val cancelled = code == BiometricPrompt.ERROR_USER_CANCELED ||
                        code == BiometricPrompt.ERROR_NEGATIVE_BUTTON || code == BiometricPrompt.ERROR_CANCELED
                    if (continuation.isActive) {
                        continuation.resume(if (cancelled) Confirmation.Cancelled else Confirmation.Failed(message.toString()))
                    }
                }
                // A finger that does not match is not the end: the prompt stays open.
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build()
        )
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    }
