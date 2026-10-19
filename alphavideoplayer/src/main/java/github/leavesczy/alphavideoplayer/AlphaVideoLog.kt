package github.leavesczy.alphavideoplayer

/** Optional logcat diagnostics. Tag is `AlphaVideoPlayer`. Disabled by default. */
object AlphaVideoLog {

    private const val TAG = "AlphaVideoPlayer"

    @Volatile
    var enabled = false

    fun d(component: String, message: String) {
        if (enabled) {
            android.util.Log.d(TAG, format(component = component, message = message))
        }
    }

    fun i(component: String, message: String) {
        if (enabled) {
            android.util.Log.i(TAG, format(component = component, message = message))
        }
    }

    fun w(component: String, message: String, throwable: Throwable? = null) {
        if (!enabled) {
            return
        }
        if (throwable != null) {
            android.util.Log.w(TAG, format(component = component, message = message), throwable)
        } else {
            android.util.Log.w(TAG, format(component = component, message = message))
        }
    }

    fun e(component: String, message: String, throwable: Throwable? = null) {
        if (!enabled) {
            return
        }
        if (throwable != null) {
            android.util.Log.e(TAG, format(component = component, message = message), throwable)
        } else {
            android.util.Log.e(TAG, format(component = component, message = message))
        }
    }

    private fun format(component: String, message: String): String {
        return if (component.isEmpty()) message else "[$component] $message"
    }

}
