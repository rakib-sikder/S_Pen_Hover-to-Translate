package com.sikder.spentranslator

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sikder.spentranslator.services.CaptureService
import com.sikder.spentranslator.services.HoverWatchService
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_OVERLAY    = 1001
        const val REQUEST_PROJECTION = 1002

        val LANGUAGE_NAMES = arrayOf(
            "English", "Bengali", "Arabic", "Chinese", "French", "German",
            "Hindi", "Indonesian", "Japanese", "Korean", "Portuguese",
            "Russian", "Spanish", "Turkish"
        )
        val LANGUAGE_CODES = arrayOf(
            TranslateLanguage.ENGLISH, TranslateLanguage.BENGALI, TranslateLanguage.ARABIC,
            TranslateLanguage.CHINESE, TranslateLanguage.FRENCH, TranslateLanguage.GERMAN,
            TranslateLanguage.HINDI, TranslateLanguage.INDONESIAN, TranslateLanguage.JAPANESE,
            TranslateLanguage.KOREAN, TranslateLanguage.PORTUGUESE, TranslateLanguage.RUSSIAN,
            TranslateLanguage.SPANISH, TranslateLanguage.TURKISH
        )
    }

    private lateinit var btnToggleService: Button
    private lateinit var tvStatus: TextView
    private lateinit var spinnerTarget: Spinner
    private lateinit var btnDownloadModel: Button
    private lateinit var btnClose: Button

    private var selectedLangCode = TranslateLanguage.ENGLISH
    private var selectedLangName = "English"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggleService = findViewById(R.id.btnToggleService)
        tvStatus         = findViewById(R.id.tvStatus)
        spinnerTarget    = findViewById(R.id.spinnerTargetLanguage)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)
        btnClose         = findViewById(R.id.btnClose)

        setupLanguageSpinner()
        setupButtons()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupLanguageSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, LANGUAGE_NAMES)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTarget.adapter = adapter

        spinnerTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                selectedLangCode = LANGUAGE_CODES[pos]
                selectedLangName = LANGUAGE_NAMES[pos]
                CaptureService.targetLanguage     = selectedLangCode
                CaptureService.targetLanguageName = selectedLangName
                HoverWatchService.targetLanguage     = selectedLangCode
                HoverWatchService.targetLanguageName = selectedLangName
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupButtons() {
        btnToggleService.setOnClickListener {
            when {
                !Settings.canDrawOverlays(this) -> {
                    // Step 1: request overlay permission
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")),
                        REQUEST_OVERLAY
                    )
                }
                !isAccessibilityServiceEnabled() -> {
                    // Step 2: enable accessibility service
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                CaptureService.instance != null -> {
                    stopService(Intent(this, CaptureService::class.java))
                    HoverWatchService.instance?.deactivate()
                    updateStatus()
                }
                else -> {
                    // All permissions granted and service not running — start it
                    val mgr = getSystemService(MediaProjectionManager::class.java)
                    startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_PROJECTION)
                }
            }
        }

        btnDownloadModel.setOnClickListener {
            downloadTranslationModel()
        }

        btnClose.setOnClickListener {
            getSharedPreferences("spen_prefs", MODE_PRIVATE)
                .edit().putBoolean("is_active", false).commit()
            stopService(Intent(this, CaptureService::class.java))
            HoverWatchService.instance?.deactivate()
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun downloadTranslationModel() {
        btnDownloadModel.isEnabled = false
        btnDownloadModel.text = "Downloading…"

        android.util.Log.d("SPenTranslate", "Download started for: $selectedLangCode")  // ← add

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(selectedLangCode)
            .build()

        Translation.getClient(options)
            .downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                android.util.Log.d("SPenTranslate", "Download SUCCESS")  // ← add
                btnDownloadModel.text = "Model ready ✓"
                tvStatus.text = "Translation model downloaded. Ready to use!"
            }
            .addOnFailureListener { e ->
                android.util.Log.e("SPenTranslate", "Download FAILED: ${e.message}")  // ← add
                btnDownloadModel.isEnabled = true
                btnDownloadModel.text = "Download translation model"
                tvStatus.text = "Download failed: ${e.message}"
            }
    }
    // FIX 4: updateStatus() now reflects whether CaptureService is actually running
    private fun updateStatus() {
        val hasOverlay       = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()
        val isRunning        = CaptureService.instance != null

        tvStatus.text = when {
            !hasOverlay       -> "① Grant overlay permission first"
            !hasAccessibility -> "② Enable S Pen OCR in Accessibility Settings"
            isRunning         -> "✓ Running. Hover S Pen over text to translate."
            else              -> "✓ Ready. Tap Start to begin screen capture."
        }

        btnToggleService.text = when {
            !hasOverlay       -> "Grant overlay permission"
            !hasAccessibility -> "Enable Accessibility Service"
            isRunning         -> "Stop service"
            else              -> "Start"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${HoverWatchService::class.java.name}"
        return Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(service) == true
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY -> updateStatus()
            REQUEST_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startForegroundService(
                        Intent(this, CaptureService::class.java).apply {
                            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                            putExtra(CaptureService.EXTRA_DATA, data)
                            putExtra(CaptureService.EXTRA_TARGET_LANG, selectedLangCode)
                            putExtra(CaptureService.EXTRA_TARGET_LANG_NAME, selectedLangName)
                        }
                    )
                    HoverWatchService.isActive = true
                    getSharedPreferences("spen_prefs", MODE_PRIVATE)
                        .edit().putBoolean("is_active", true).apply()
                    HoverWatchService.instance?.showModeButtonPublic()
                    updateStatus()
                }
            }
        }
    }
}