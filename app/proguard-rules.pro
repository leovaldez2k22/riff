# Riff R8 rules (plan W4). Libraries ship their own consumer rules (kotlinx.serialization, Media3,
# ML Kit, OkHttp, Ktor, Coil, quickjs-kt). Add a rule here only for something that broke, and say why.

# CipherBridgeInstaller replaces this field by name to run QuickJS in the :solver process (W1.2).
# Without it the bridge falls back to the in-process cipher and a QuickJS crash kills playback again.
-keepclassmembers class com.metrolist.innertubex.extraction.InnerTubeExtractor {
    com.metrolist.innertubex.extraction.ExtractionCipherService cipherService;
}
