package com.musicglass.personal;

import com.metrolist.innertubex.extraction.ExtractionCipherService;
import com.metrolist.innertubex.extraction.InnerTubeExtractor;
import com.metrolist.innertubex.models.response.PlayerResponse;
import java.lang.reflect.Field;
import java.util.List;
import kotlin.Unit;
import kotlin.coroutines.Continuation;

/**
 * Points innertubex's extractor at {@link CipherBackend} (the out-of-process solver).
 *
 * <p>innertubex 0.7.2 accepts only its own in-process cipher: {@code ExtractionCipherService} and
 * the constructor that takes one are Kotlin {@code internal}. Both are public in the compiled
 * library, so Java can implement the interface (checked at compile time). The one unchecked step is
 * replacing the extractor's {@code cipherService} field; {@code CipherBridgeTest} verifies it on
 * every build. Remove this class once innertubex exposes a public way to supply the cipher.
 */
final class CipherBridgeInstaller {
    private CipherBridgeInstaller() {}

    /** Replaces the extractor's cipher. Throws if innertubex's internals changed; the caller then refuses online resolution. */
    static void install(InnerTubeExtractor extractor, CipherBackend backend) throws ReflectiveOperationException {
        Field field = InnerTubeExtractor.class.getDeclaredField("cipherService");
        if (!field.getType().equals(ExtractionCipherService.class)) {
            throw new NoSuchFieldException("cipherService has type " + field.getType().getName());
        }
        field.setAccessible(true);
        field.set(extractor, new Bridge(backend));
    }

    /** Package-private for tests. */
    static Object installed(InnerTubeExtractor extractor) throws ReflectiveOperationException {
        Field field = InnerTubeExtractor.class.getDeclaredField("cipherService");
        field.setAccessible(true);
        return field.get(extractor);
    }

    static final class Bridge implements ExtractionCipherService {
        final CipherBackend backend;

        Bridge(CipherBackend backend) { this.backend = backend; }

        @Override public Object initialize(Continuation<? super Unit> continuation) {
            return backend.initialize(continuation);
        }

        @Override public Object preloadPlayerCode(String playerUrl, Continuation<? super Unit> continuation) {
            return backend.preloadPlayerCode(playerUrl, continuation);
        }

        @Override public Object prewarmEjs(Continuation<? super Unit> continuation) {
            return backend.prewarmEjs(continuation);
        }

        @Override public Object processFormats(String playerUrl, List<PlayerResponse.StreamingData.Format> formats,
                                               Continuation<? super List<PlayerResponse.StreamingData.Format>> continuation) {
            return backend.processFormats(playerUrl, formats, continuation);
        }
    }
}
