# Keep Samsung SDK classes
-keep class com.samsung.** { *; }
-keep class samsung.** { *; }
-dontwarn com.samsung.**
-dontwarn samsung.**

# Keep ML Kit classes
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Keep your model class
-keep class com.sikder.spentranslator.model.** { *; }
