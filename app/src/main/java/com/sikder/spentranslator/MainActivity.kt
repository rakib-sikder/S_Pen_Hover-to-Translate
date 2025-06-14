package com.sikder.spentranslator

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.sikder.spentranslator.services.FloatingControlService
import com.sikder.spentranslator.services.HoverTranslateService
import com.sikder.spentranslator.services.InstructionTooltipService
import com.sikder.spentranslator.services.MyTextSelectionService

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    // --- UI Views ---
    // We will now show/hide the permission setup view
    private lateinit var setupView: LinearLayout
    private lateinit var mainContentView: LinearLayout

    private lateinit var btnToggleSelectToTranslate: Button
    private lateinit var etSourceText: EditText
    private lateinit var btnTranslateInApp: Button
    private lateinit var tvTargetText: TextView
    private lateinit var spinnerSourceLang: Spinner
    private lateinit var spinnerTargetLang: Spinner

    private lateinit var sharedPreferences: SharedPreferences

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
    )

    companion object {
        const val ACTION_UPDATE_UI = "com.sikder.spentranslator.ACTION_UPDATE_UI"
        const val ACTION_REQUEST_SCREEN_CAPTURE = "com.sikder.spentranslator.ACTION_REQUEST_SCREEN_CAPTURE"
        private const val NOTIFICATION_PERMISSION_REQUEST = 400
    }

    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_UI) {
                Log.d(TAG, "Received UI update broadcast.")
                updateUiState()
            }
        }
    }

    // --- ActivityResultLaunchers ---
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Log.d(TAG, "Returned from a settings screen.")
        // onResume will handle the UI update
    }
    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.i(TAG, "Screen capture permission GRANTED by user.")
            MyTextSelectionService.mediaProjectionResultCode = result.resultCode
            MyTextSelectionService.mediaProjectionIntent = result.data
            Toast.makeText(this, "Screen capture enabled. Please select text again to use OCR.", Toast.LENGTH_LONG).show()
        } else {
            Log.w(TAG, "Screen capture permission DENIED by user.")
            Toast.makeText(this, "Screen capture is needed for OCR on some apps.", Toast.LENGTH_SHORT).show()
        }
    }


    // --- Activity Lifecycle ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("SpentTranslatorPrefs", Context.MODE_PRIVATE)
        initializeViews()
        setupLanguageSpinners()
        setupClickListeners()
        requestNotificationPermission()

        LocalBroadcastManager.getInstance(this).registerReceiver(uiUpdateReceiver, IntentFilter(ACTION_UPDATE_UI))
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Checking permissions and updating UI.")
        checkPermissionsAndShowUI()
    }

    // Inside MainActivity.kt

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(uiUpdateReceiver)

        // *** ADD THIS CODE TO FIX THE ANR ***
        // Explicitly stop all running services to ensure their views are removed correctly.
        Log.d(TAG, "MainActivity is being destroyed. Stopping all services.")
        stopService(Intent(this, MyTextSelectionService::class.java))
        stopService(Intent(this, FloatingControlService::class.java))
        stopService(Intent(this, HoverTranslateService::class.java))
        stopService(Intent(this, InstructionTooltipService::class.java))
    }

    // --- UI Setup and Logic ---

    private fun initializeViews() {
        setupView = findViewById(R.id.setupView)
        mainContentView = findViewById(R.id.mainContentView)
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

        val savedSourceLangCode = sharedPreferences.getString("source_lang_code", TranslateLanguage.ENGLISH)
        val savedTargetLangCode = sharedPreferences.getString("target_lang_code", TranslateLanguage.BENGALI)

        spinnerSourceLang.setSelection(supportedLanguages.indexOfFirst { it.code == savedSourceLangCode }.coerceAtLeast(0))
        spinnerTargetLang.setSelection(supportedLanguages.indexOfFirst { it.code == savedTargetLangCode }.coerceAtLeast(0))

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                saveLanguagePreferences()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerSourceLang.onItemSelectedListener = listener
        spinnerTargetLang.onItemSelectedListener = listener
    }

    private fun saveLanguagePreferences() {
        val sourceLangCode = supportedLanguages[spinnerSourceLang.selectedItemPosition].code
        val targetLangCode = supportedLanguages[spinnerTargetLang.selectedItemPosition].code
        sharedPreferences.edit()
            .putString("source_lang_code", sourceLangCode)
            .putString("target_lang_code", targetLangCode)
            .apply()
    }

    private fun setupClickListeners() {
        btnTranslateInApp.setOnClickListener { performInAppTranslation() }
        btnToggleSelectToTranslate.setOnClickListener { toggleSelectToTranslateFeature() }
    }

    // --- Permission and Feature Control Logic ---

    private fun checkPermissionsAndShowUI() {
        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceSystemEnabled()

        if (hasOverlay && hasAccessibility) {
            // All permissions granted, show the main UI
            mainContentView.visibility = View.VISIBLE
            setupView.visibility = View.GONE
            updateUiState()
        } else {
            // Permissions are missing, hide main UI and guide user
            mainContentView.visibility = View.GONE
            setupView.visibility = View.VISIBLE
            // Automatically launch the next required permission screen
            if (!hasOverlay) {
                requestOverlayPermission()
            } else if (!hasAccessibility) {
                requestAccessibilityPermission()
            }
        }
    }

    private fun updateUiState() {
        btnToggleSelectToTranslate.text = if (MyTextSelectionService.isFeatureActive) getString(R.string.stop_select_to_translate) else getString(R.string.start_select_to_translate)
    }

    private fun performInAppTranslation() {
        val sourceText = etSourceText.text.toString().trim()
        if (sourceText.isNotBlank()) {
            val sourceLangCode = supportedLanguages[spinnerSourceLang.selectedItemPosition].code
            val targetLangCode = supportedLanguages[spinnerTargetLang.selectedItemPosition].code
            tvTargetText.text = "Translating..."
            TranslationApiClient.translate(sourceText, sourceLangCode, targetLangCode) { translatedText ->
                runOnUiThread {
                    if (translatedText != null) {
                        tvTargetText.text = translatedText
                    } else {
                        tvTargetText.text = getString(R.string.translation_failed)
                    }
                }
            }
        } else {
            Toast.makeText(this, getString(R.string.enter_text_to_translate), Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant Overlay Permission", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            settingsLauncher.launch(intent)
        }
    }

    private fun requestAccessibilityPermission() {
        if (!isAccessibilityServiceSystemEnabled()) {
            Toast.makeText(this, "Please enable the '${getString(R.string.accessibility_service_label)}' in Settings", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            settingsLauncher.launch(intent)
        }
    }

    private fun toggleSelectToTranslateFeature() {
        val serviceIntent = Intent(this, MyTextSelectionService::class.java)
        if (MyTextSelectionService.isFeatureActive) {
            serviceIntent.action = MyTextSelectionService.ACTION_STOP_FEATURE
        } else {
            serviceIntent.action = MyTextSelectionService.ACTION_START_FEATURE
            val minimizeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(minimizeIntent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && serviceIntent.action == MyTextSelectionService.ACTION_START_FEATURE) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
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
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            if (!(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, getString(R.string.notification_permission_denied), Toast.LENGTH_LONG).show()
            }
        }
    }
}