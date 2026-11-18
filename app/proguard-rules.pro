# Add project specific ProGuard rules here.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# --------------------------------------------------------------------------
# 1. Native JNI & Go / Xray / HevSocks5Tunnel bridges
# --------------------------------------------------------------------------
-keep class go.** { *; }
-keep class libv2ray.** { *; }

# libhev-socks5-tunnel.so resolves com/v2ray/ang/service/TProxyService via JNI RegisterNatives
-keep class com.v2ray.ang.service.TProxyService { *; }
-keep class com.v2ray.ang.service.TProxyService$Companion { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --------------------------------------------------------------------------
# 2. Gson-serialized DTOs, MMKV models, and remote server manifest models
# --------------------------------------------------------------------------
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }

-keep class com.v2ray.ang.dto.** { *; }
-keep class com.v2ray.ang.enums.** { *; }
-keep class com.v2ray.ang.mcqueen.RemoteServerManifestDto { *; }
-keep class com.v2ray.ang.mcqueen.RemoteServerEntryDto { *; }
-keep class com.v2ray.ang.mcqueen.CountryAvailability { *; }
-keep class com.v2ray.ang.mcqueen.CountryUiModel { *; }
-keep class com.v2ray.ang.mcqueen.ValidatedServer { *; }
-keep class com.v2ray.ang.mcqueen.ManifestParseResult { *; }
-keep class com.v2ray.ang.mcqueen.CandidateLatency { *; }
-keep class com.v2ray.ang.mcqueen.ManifestValidatorState { *; }
-keep class com.v2ray.ang.mcqueen.CachedManifestSummary { *; }

# --------------------------------------------------------------------------
# 3. Java Serializable classes passed across multi-process Intent/Bundle IPC
# --------------------------------------------------------------------------
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# --------------------------------------------------------------------------
# 4. MMKV & AndroidX WorkManager multiprocess / Service / Receiver entrypoints
# --------------------------------------------------------------------------
-keep class com.tencent.mmkv.** { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.multiprocess.RemoteCoroutineWorker { *; }

