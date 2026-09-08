# Add project specific ProGuard rules here.
#
# Real bug found and fixed 2026-09-04: this file was left as a stub ("add rules once the
# DTO/model set is finalized") and never actually filled in, so every release build ever
# produced from this project has been minified with ZERO protection for kotlinx.serialization
# or BouncyCastle. Confirmed live on a real device: a fresh release-build install could log in,
# bind a vehicle, and pair fine, but GET /v1/tariffs/active's response never cached — Settings'
# "Tariff signature" tile stuck on "No cached tariff" forever, with GPS/network/heartbeat all
# healthy. TariffCache.refresh() swallows its exception via runCatching with no logging (by
# design, see that class's doc), so this failed completely silently. Root cause: R8 strips the
# reflection-based bits both TariffCache.refresh()'s two real network/crypto dependencies need
# (kotlinx.serialization's generated $$serializer classes for TariffDto, and BouncyCastle's
# Signature.getInstance("Ed25519", BouncyCastleProvider()) service-provider lookup) unless
# explicitly kept — and nothing here ever kept them. The debug build (isMinifyEnabled = false)
# never exercised this path, which is why it was never seen until distributing a release build.

# --- kotlinx.serialization (official rules: https://github.com/Kotlin/kotlinx.serialization) ---
# Every @Serializable class in this app (TariffDto, GeocodeResult, DirectionsRoute, the
# driver-engagement DTOs, etc.) is decoded via Retrofit's converter-kotlinx-serialization —
# without these, deserialization throws at runtime in a release build only.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class au.com.threesixty.cabdispatch.**$$serializer { *; }
-keepclassmembers class au.com.threesixty.cabdispatch.** {
    *** Companion;
}
-keepclasseswithmembers class au.com.threesixty.cabdispatch.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- BouncyCastle (bcprov-jdk18on) ---
# Ed25519TariffSignatureVerifier resolves "Ed25519" via BouncyCastleProvider's JCA service-
# provider registration, which is reflection-based (java.security.Provider service lookup) —
# exactly the kind of thing R8 removes by default with no explicit keep.
-keep class org.bouncycastle.** { *; }
-keepnames class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
