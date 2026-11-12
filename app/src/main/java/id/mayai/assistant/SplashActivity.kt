package id.mayai.assistant

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {

    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        setupSystemUi()
        applyAdaptiveInsets()
        playEnterAnimation()
        scheduleNavigate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Layout-land / layout-sw600dp di-inflate ulang agar art & branding ikut orientasi
        setContentView(R.layout.activity_splash)
        setupSystemUi()
        applyAdaptiveInsets()
        playEnterAnimation()
    }

    private fun setupSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { c ->
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun applyAdaptiveInsets() {
        val branding = findViewById<LinearLayout>(R.id.splashBranding) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(branding) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val bottom = maxOf(bars.bottom, cutout.bottom) + dp(16)
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
            insets
        }
        branding.requestApplyInsets()
    }

    private fun playEnterAnimation() {
        val image = findViewById<View>(R.id.splashImage) ?: return
        val branding = findViewById<View>(R.id.splashBranding)
        image.alpha = 0f
        image.scaleX = 1.06f
        image.scaleY = 1.06f
        image.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(900)
            .setInterpolator(DecelerateInterpolator())
            .start()
        branding?.let {
            it.alpha = 0f
            it.translationY = dp(24).toFloat()
            it.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(280)
                .setDuration(650)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun scheduleNavigate() {
        lifecycleScope.launch {
            delay(1700)
            goMain()
        }
    }

    private fun goMain() {
        if (navigated) return
        navigated = true
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
