package com.v2ray.ang.ui.compose

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class ToastType {
    NORMAL,
    SUCCESS,
    ERROR,
    INFO
}

object AppSnackbarManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var activeHostState: SnackbarHostState? = null

    fun attach(hostState: SnackbarHostState) {
        activeHostState = hostState
    }

    fun detach(hostState: SnackbarHostState) {
        if (activeHostState === hostState) {
            activeHostState = null
        }
    }

    fun show(
        message: CharSequence,
        type: ToastType = ToastType.NORMAL,
        long: Boolean = false
    ): Boolean {
        val host = activeHostState ?: return false
        scope.launch {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(
                message = message.toString(),
                duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short
            )
        }
        return true
    }
}

/**
 * Styled SnackbarHost aligned with the McQueen Effortless Performance palette.
 */
@Composable
fun AppSnackbarHost(
    hostState: SnackbarHostState = SnackbarHostState(),
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
    ) { data: SnackbarData ->
        Snackbar(
            snackbarData = data,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            actionColor = McQueenCrimsonReadable
        )
    }
}
