# Riff R8 rules (plan W4). Libraries ship their own consumer rules (kotlinx.serialization, Media3,
# OkHttp, Ktor, Coil, quickjs-kt). Add a rule here only for something that broke, and say why.

# CipherBridgeInstaller replaces this field by name to run QuickJS in the :solver process (W1.2).
# Without it the bridge cannot be installed and online playback is refused (scripts/quality/check_solver_seam.py).
-keepclassmembers class com.metrolist.innertubex.extraction.InnerTubeExtractor {
    com.metrolist.innertubex.extraction.ExtractionCipherService cipherService;
}

