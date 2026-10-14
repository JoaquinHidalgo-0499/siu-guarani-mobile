# Reglas R8 / Proguard para optimización de bajo consumo (Target: Moto E14 / Android Go)

# UniFFI y JNA: Conservar bindings y llamadas nativas
-keep class uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * {
    native <methods>;
}

# Google Tink
-keepclassmembers class * extends com.google.crypto.tink.** { *; }

# Mantener modelos de datos consumidos por UI
-keep class ar.edu.unju.fi.guarani.** { *; }
