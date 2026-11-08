# Automix stage 4: ONNX Runtime's native code creates and reads its Java classes (OnnxTensor, OrtSession, the
# exceptions) by name through JNI, which R8 cannot see; its own rules keep only the telemetry classes.
-keep class ai.onnxruntime.** { *; }
