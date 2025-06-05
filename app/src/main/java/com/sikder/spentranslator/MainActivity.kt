package com.sikder.spentranslator

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.sikder.spentranslator.services.MyTextSelectionService

// Data class to hold language information
data class Language(val displayName: String, val code: String)

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    // UI elements for feature control
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnEnableOverlay: Button
    private lateinit var btnToggleSelectToTranslate: Button

    // UI elements for in-app translation
    private lateinit var etSourceText: EditText
    private lateinit var btnTranslateInApp: Button
    private lateinit var tvTargetText: TextView
    private lateinit var spinnerSourceLang: Spinner
    private lateinit var spinnerTargetLang: Spinner

    private lateinit var sharedPreferences: SharedPreferences

    // A list of languages you want to support in the dropdowns
    private val supportedLanguages = listOf(
        Language("English", TranslateLanguage.ENGLISH),
        Language("Bengali", TranslateLanguage.BENGALI),
        Language("Spanish", TranslateLanguage.SPANISH),
        Language("Hindi", TranslateLanguage.HINDI),
        Language("Arabic", TranslateLanguage.ARABIC),
        Language("French", TranslateLanguage.FRENCH),
        Language("German", TranslateLanguage.GERMAN),
        Language("Japanese", TranslateLanguage.JAPANESE),
        Language("Korean", TranslateLanguage.KOREAN),
        Language("Russian", TranslateLanguage.RUSSIAN)
        // Add more languages from com.google.mlkit.nl.translate.TranslateLanguage here
    )

    companion object {
        const val ACTION_UPDATE_UI = "com.sikder.spentranslator.ACTION_UPDATE_UI"
        private const val NOTIFICATION_PERMISSION_REQUEST = 400
    }

    // Listens for broadcasts from the service to update the UI
    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_UI) {
                Log.d(TAG, "Received UI update broadcast from service.")
                updateButtonStates()
            }
        }
    }

    // Modern way to handle returning from settings screens
    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            Log.d(TAG, "Returned from a settings screen, updating button states.")
            updateButtonStates()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("SpentTranslatorPrefs", Context.MODE_PRIVATE)

        initializeViews()
        setupLanguageSpinners()
        setupClickListeners()

        // For Android 13+, ask for notification permission needed for the foreground service
        requestNotificationPermission()

        // Register a receiver to get UI update requests from the service
        LocalBroadcastManager.getInstance(this).registerReceiver(uiUpdateReceiver, IntentFilter(ACTION_UPDATE_UI))
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Updating button states.")
        updateButtonStates()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the receiver to prevent memory leaks
        LocalBroadcastManager.getInstance(this).unregisterReceiver(uiUpdateReceiver)
    }

    private fun initializeViews() {
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnEnableOverlay = findViewById(R.id.btnEnableOverlay)
        btnToggleSelectToTranslate = findViewById(R.id.btnToggleSelectToTranslate)
        etSourceText = findViewById(R.id.etSourceText)
        btnTranslateInApp = findViewById(R.id.btnTranslateInApp)
        tvTargetText = findViewById(R.id.tvTargetText)
        spinnerSourceLang = findViewById(R.id.spinnerSourceLang)
        spinnerTargetLang = findViewById(R.id.spinnerTargetLang)
    }

    private fun setupLanguageSpinners() {
        val languageDisplayNames = supportedLanguages.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageDisplayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerSourceLang.adapter = adapter
        spinnerTargetLang.adapter = adapter

        // Restore saved language preferences
        val savedSourceLangCode = sharedPreferences.getString("source_lang_code", TranslateLanguage.ENGLISH)
        val savedTargetLangCode = sharedPreferences.getString("target_lang_code", TranslateLanguage.BENGALI)

        val sourcePosition = supportedLanguages.indexOfFirst { it.code == savedSourceLangCode }
        val targetPosition = supportedLanguages.indexOfFirst { it.code == savedTargetLangCode }

        spinnerSourceLang.setSelection(if (sourcePosition != -1) sourcePosition else 0)
        spinnerTargetLang.setSelection(if (targetPosition != -1) targetPosition else 1) // Default to Bengali if not found

        // Listener to save preferences when a new language is selected
        val languageSelectionListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                saveLanguagePreferences()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerSourceLang.onItemSelectedListener = languageSelectionListener
        spinnerTargetLang.onItemSelectedListener = languageSelectionListener
    }

    private fun saveLanguagePreferences() {
        val sourceLangCode = supportedLanguages[spinnerSourceLang.selectedItemPosition].code
        val targetLangCode = supportedLanguages[spinnerTargetLang.selectedItemPosition].code

        Log.d(TAG, "Saving language preferences: Source=$sourceLangCode, Target=$targetLangCode")
        sharedPreferences.edit()
            .putString("source_lang_code", sourceLangCode)
            .putString("target_lang_code", targetLangCode)
            .apply()
    }

    private fun setupClickListeners() {
        btnTranslateInApp.setOnClickListener { performInAppTranslation() }
        btnEnableAccessibility.setOnClickListener { requestAccessibilityPermission() }
        btnEnableOverlay.setOnClickListener { requestOverlayPermission() }
        btnToggleSelectToTranslate.setOnClickListener { toggleSelectToTranslateFeature() }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            try {
                settingsLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.overlay_settings_error), Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Error opening Overlay Permission Settings", e)
            }
        }
    }

    private fun requestAccessibilityPermission() {
        if (!isAccessibilityServiceSystemEnabled()) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            Toast.makeText(this, "Please find and enable '${getString(R.string.accessibility_service_label)}'", Toast.LENGTH_LONG).show()
            try {
                settingsLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.accessibility_settings_error), Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Error opening Accessibility Settings", e)
            }
        }
    }

    private fun toggleSelectToTranslateFeature() {
        if (!isAccessibilityServiceSystemEnabled() || !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.select_to_translate_permissions_needed), Toast.LENGTH_LONG).show()
            return
        }

        val serviceIntent = Intent(this, MyTextSelectionService::class.java)
        if (MyTextSelectionService.isFeatureActive) {
            serviceIntent.action = MyTextSelectionService.ACTION_STOP_FEATURE
            Log.d(TAG, "Sending STOP_FEATURE command to service.")
        } else {
            serviceIntent.action = MyTextSelectionService.ACTION_START_FEATURE
            Log.d(TAG, "Sending START_FEATURE command to service.")
            // Minimize app after starting the feature
            val minimizeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(minimizeIntent)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && serviceIntent.action == MyTextSelectionService.ACTION_START_FEATURE) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting/stopping MyTextSelectionService", e)
        }
    }

    private fun performInAppTranslation() {
        val sourceText = etSourceText.text.toString().trim()
        if (sourceText.isNotBlank()) {
            val sourceLangCode = supportedLanguages[spinnerSourceLang.selectedItemPosition].code
            val targetLangCode = supportedLanguages[spinnerTargetLang.selectedItemPosition].code

            tvTargetText.text = "Translating..." // Give immediate feedback

            TranslationApiClient.translate(sourceText, sourceLangCode, targetLangCode) { translatedText ->
                runOnUiThread {
                    if (translatedText != null) {
                        tvTargetText.text = translatedText
                    } else {
                        tvTargetText.text = getString(R.string.translation_failed)
                        Toast.makeText(this, getString(R.string.translation_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(this, getString(R.string.enter_text_to_translate), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateButtonStates() {
        val overlayEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val accessibilityEnabled = isAccessibilityServiceSystemEnabled()

        btnEnableOverlay.isEnabled = !overlayEnabled
        btnEnableOverlay.text = if (overlayEnabled) getString(R.string.overlay_permission_granted_text) else getString(R.string.enable_overlay_permission)

        btnEnableAccessibility.isEnabled = !accessibilityEnabled
        btnEnableAccessibility.text = if (accessibilityEnabled) getString(R.string.accessibility_service_enabled_text) else getString(R.string.enable_accessibility_service)

        val canToggleFeature = overlayEnabled && accessibilityEnabled
        btnToggleSelectToTranslate.isEnabled = canToggleFeature
        if (!canToggleFeature) {
            btnToggleSelectToTranslate.text = getString(R.string.select_to_translate_permissions_needed)
        } else {
            btnToggleSelectToTranslate.text = if (MyTextSelectionService.isFeatureActive) getString(R.string.stop_select_to_translate) else getString(R.string.start_select_to_translate)
        }
    }

    private fun isAccessibilityServiceSystemEnabled(): Boolean {
        val serviceComponent = ComponentName(this, MyTextSelectionService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServicesSetting?.contains(serviceComponent.flattenToString(), ignoreCase = true) == true
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    Toast.makeText(this, "Notification permission is needed for service status.", Toast.LENGTH_LONG).show()
                }
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, getString(R.string.notification_permission_granted), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.notification_permission_denied), Toast.LENGTH_LONG).show()
            }
        }
    }
}