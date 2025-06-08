# S Pen Hover-to-Translate

**S Pen Hover-to-Translate** is an intelligent Android application designed to provide seamless, on-the-fly translations without ever leaving your current app. It leverages the power of Samsung's S Pen for an intuitive hover-to-select experience and offers robust fallback options, including OCR, to capture and translate text from any source on your screen.

![App Demo](https://i.imgur.com/image.png) ## ✨ Key Features

- **S Pen Integration (Conceptual):** Designed with S Pen users in mind, allowing for precise text selection and a unique hover-based interaction model.
- **Universal Select-to-Translate:** Activate the feature and select text in **any app**—browsers, social media, PDFs, or note-taking apps—to get an instant translation in a floating tooltip.
- **Intelligent Text Capture (Waterfall Strategy):**
    - **Priority 1 (Modern API):** Uses the latest `getSelectedText()` API for the most efficient text capture on Android 14+.
    - **Priority 2 (Standard Accessibility):** Falls back to standard accessibility properties (`textSelectionStart`/`End`) for broad compatibility with most apps.
    - **Priority 3 (OCR - Last Resort):** For difficult-to-read sources like certain PDFs or apps with custom UIs, it uses **On-Device OCR** with screen capture to recognize text that would otherwise be inaccessible.
- **Floating Language Controller:**
    - When active, a persistent floating widget gives you full control.
    - Instantly change the source and target languages without opening the main app.
    - Includes an **"Auto-Detect"** option for the source language, powered by ML Kit Language ID.
- **On-Device Machine Learning:**
    - All language detection and translation are performed securely on your device using **Google's ML Kit**.
    - Works offline after the required language models have been downloaded.
- **In-App Translator:** Includes a standard screen for manually typing and translating text.

## 📸 Screenshots

| In-App Translator | Floating Language Widget | Translation Tooltip |
| :---: |:---: |:---: |
| ![In-App UI](https://i.imgur.com/image.png) | ![Floating Widget](https://i.imgur.com/image.png) | ![Tooltip Example](https://i.imgur.com/image.png) |
*(Suggestion: Replace these placeholder images with actual screenshots of your app.)*

## 🛠️ Setup & Build

To set up and build this project yourself, follow these steps:

1.  **Clone the repository:**
    ```bash
    git clone [https://github.com/rakib-sikder/S_Pen_Hover-to-Translate.git](https://github.com/rakib-sikder/S_Pen_Hover-to-Translate.git)
    ```
2.  **Open in Android Studio:** Open the cloned project in the latest stable version of Android Studio.
3.  **Gradle Sync:** Allow Gradle to sync and download all the required dependencies listed in `app/build.gradle.kts`.
4.  **Build the project:**
    * Go to **Build > Rebuild Project**.
    * The project requires `compileSdk = 34` or higher. Ensure you have the Android 14 SDK installed via the SDK Manager in Android Studio.

## 🚀 How to Use

1.  **Grant Permissions:** Upon first launch, the app will automatically guide you to the necessary system settings pages to grant permissions:
    * **Overlay Permission:** To allow the app to display tooltips over other apps.
    * **Accessibility Service:** This is the core permission that allows the app to detect your text selections. Find "S Pen Hover-to-Translate" in the list and enable it.
    * **Notification Permission (Android 13+):** Needed for the service's status notification.
2.  **Activate Select-to-Translate:**
    * In the app, tap the **"Start Select-to-Translate"** button.
    * The app will minimize, and the floating language widget will appear.
3.  **Translate Anywhere:**
    * Go to any other app, website, or PDF.
    * Select text using your finger or S Pen.
    * A feedback popup will confirm the text you selected, followed by a tooltip with the translated text.
    * Change languages at any time using the floating widget.
4.  **Stop the Feature:** Tap the **"Stop Select-to-Translate"** button in the main app to dismiss the floating widget and deactivate the feature.

## 📚 Key Dependencies

This project relies on several key libraries from the Android Jetpack and Google ML Kit ecosystems:

- **AndroidX Libraries:** `appcompat`, `core-ktx`, `constraintlayout` for core app functionality.
- **Google Material Design:** For modern UI components.
- **ML Kit Translate:** For powerful, on-device, offline-capable text translation.
- **ML Kit Text Recognition:** For the OCR fallback feature.
- **ML Kit Language ID:** For the "Auto-Detect" source language feature.

## 🤝 Contributing

Contributions are welcome! If you'd like to contribute, please feel free to fork the repository and submit a pull request. You can help by:

-   Improving the precision of OCR text selection.
-   Adding more languages to the selection list.
-   Enhancing the UI/UX of the floating widget and tooltips.
-   Fixing any open bugs or optimizing performance.

## 📄 License

This project is licensed under the MIT License. See the [LICENSE.md](LICENSE.md) file for details.
