# Riff R8 rules (plan W4). Libraries ship their own consumer rules (kotlinx.serialization, Media3,
# OkHttp, Ktor, Coil, quickjs-kt). Add a rule here only for something that broke, and say why.

# Q18-089: failure names reach the diagnostic log (download_preferred_client_failed type=...). A renamed exception
# (ja5) could not be told apart there, so exception classes keep their names; members are still shrunk and renamed.
-keepnames class * extends java.lang.Throwable

# CipherBridgeInstaller replaces this field by name to run QuickJS in the :solver process (W1.2).
# Without it the bridge cannot be installed and online playback is refused (scripts/quality/check_solver_seam.py).
-keepclassmembers class com.metrolist.innertubex.extraction.InnerTubeExtractor {
    com.metrolist.innertubex.extraction.ExtractionCipherService cipherService;
}


# Feature 19: Play services finds the Cast options by the class name in the manifest's OPTIONS_PROVIDER_CLASS_NAME,
# which R8 does not read. Without this the release build had no RiffCastOptions, and Cast could not start.
-keep class com.musicglass.personal.RiffCastOptions { <init>(); }

# Automix stage 4: ONNX Runtime's native code creates and reads its Java classes (OnnxTensor, OrtSession, the
# exceptions) by name through JNI, which R8 cannot see; its own rules keep only the telemetry classes.
-keep class ai.onnxruntime.** { *; }
