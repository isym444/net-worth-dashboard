package com.isym444.networth.data

/**
 * Opens a fragile store (Keystore-backed encrypted preferences). Keystore trouble on OEM ROMs is often
 * transient, so: try, pause and try once more, and only then reset (which deletes saved data) and try a
 * final time. If even that fails, return null so callers degrade instead of crashing the widget.
 */
fun <T : Any> openWithRecovery(create: () -> T, reset: () -> Unit, pause: () -> Unit = { Thread.sleep(150) }): T? {
    try { return create() } catch (e: Exception) { /* maybe transient */ }
    pause()
    try { return create() } catch (e: Exception) { /* persistent */ }
    reset()
    return try { create() } catch (e: Exception) { null }
}
