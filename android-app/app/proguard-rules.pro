# Reglas R8 / Proguard para optimización de bajo consumo (Target: Moto E14 / Android Go)

# UniFFI y JNA: Conservar bindings y llamadas nativas
-keep class uniffi.** { *; }
-keepclassmembers class uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keepclassmembers class * {
    native <methods>;
}

# Google Tink: Preservar clases y managers criptográficos
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Mantener modelos de datos consumidos por UI
-keep class ar.edu.unju.fi.guarani.** { *; }

# Ignorar dependencias de escritorio (AWT) presentes en JNA no utilizadas en Android
-dontwarn java.awt.**
