# Riff R8 rules (plan W4). Libraries ship their own consumer rules (kotlinx.serialization, Media3,
# ML Kit, OkHttp, Ktor, Coil, quickjs-kt). Add a rule here only for something that broke, and say why.

# CipherBridgeInstaller replaces this field by name to run QuickJS in the :solver process (W1.2).
# Without it the bridge cannot be installed and online playback is refused (scripts/quality/check_solver_seam.py).
-keepclassmembers class com.metrolist.innertubex.extraction.InnerTubeExtractor {
    com.metrolist.innertubex.extraction.ExtractionCipherService cipherService;
}

# Q18-072: ML Kit (and Firebase components) create each ComponentRegistrar named in the manifest by reflection
# through its no-argument constructor. R8 kept the classes by name but removed the constructors, so no registrar
# was made, LanguageIdentifierImpl.Factory stayed null and translation failed in release builds only.
# scripts/quality/check_registrars.py checks this in every shrunk build.
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }
