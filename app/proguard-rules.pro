# The detection engine is plain Kotlin with no reflection; nothing to keep beyond the defaults.

# PDFBox-Android reaches for AWT-era classes that do not exist on Android and are never executed.
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**
-dontwarn javax.imageio.**
-dontwarn java.awt.**

# ML Kit loads its text recognisers by name.
-keep class com.google.mlkit.vision.text.** { *; }
