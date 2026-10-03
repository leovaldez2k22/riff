package com.musicglass.personal;

import static org.junit.Assert.*;

import com.metrolist.innertubex.extraction.ExtractionCipherService;
import com.metrolist.innertubex.extraction.InnerTubeExtractor;
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format;
import io.ktor.client.HttpClient;
import io.ktor.client.HttpClientKt;
import io.ktor.client.engine.okhttp.OkHttp;
import java.util.Collections;
import java.util.List;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import org.junit.Test;

/**
 * Guards CipherBridgeInstaller's one unchecked step: if innertubex renames or retypes the
 * extractor's cipher field, this fails at build time instead of the app silently keeping
 * QuickJS in the main process.
 */
public class CipherBridgeTest {
    private static final class FakeBackend implements CipherBackend {
        String lastPlayerUrl;

        @Override public Object initialize(Continuation<? super Unit> c) { return Unit.INSTANCE; }
        @Override public Object preloadPlayerCode(String playerUrl, Continuation<? super Unit> c) { lastPlayerUrl = playerUrl; return Unit.INSTANCE; }
        @Override public Object prewarmEjs(Continuation<? super Unit> c) { return Unit.INSTANCE; }
        @Override public Object processFormats(String playerUrl, List<Format> formats, Continuation<? super List<Format>> c) {
            lastPlayerUrl = playerUrl;
            return Collections.singletonList(new Format(140, "https://solved.example/audio", "audio/mp4", 0, null, null, null,
                null, null, null, null, null, null, null, null, null, false, null, null));
        }
        @Override public Object processNParameter(String playerUrl, String url, Continuation<? super String> c) { return url; }
    }

    private static final Continuation<Object> NO_SUSPEND = new Continuation<Object>() {
        @Override public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
        @Override public void resumeWith(Object result) { fail("fake backend never suspends"); }
    };

    private static InnerTubeExtractor extractor(HttpClient http) {
        return CipherBridgeTestSupportKt.providerShapedExtractor(http);
    }

    @Test @SuppressWarnings("unchecked")
    public void installReplacesTheExtractorCipherAndDelegates() throws Exception {
        HttpClient http = HttpClientKt.HttpClient(OkHttp.INSTANCE, cfg -> Unit.INSTANCE);
        try {
            InnerTubeExtractor extractor = extractor(http);
            assertFalse(CipherBridgeInstaller.installed(extractor) instanceof CipherBridgeInstaller.Bridge);

            FakeBackend backend = new FakeBackend();
            CipherBridgeInstaller.install(extractor, backend);
            Object installed = CipherBridgeInstaller.installed(extractor);
            assertTrue(installed instanceof CipherBridgeInstaller.Bridge);

            ExtractionCipherService cipher = (ExtractionCipherService) installed;
            Object result = cipher.processFormats("https://www.youtube.com/s/player/abc/base.js",
                Collections.emptyList(), (Continuation<? super List<Format>>) (Continuation<?>) NO_SUSPEND);
            assertEquals("https://www.youtube.com/s/player/abc/base.js", backend.lastPlayerUrl);
            assertEquals("https://solved.example/audio", ((List<Format>) result).get(0).getUrl());

            cipher.preloadPlayerCode("p2", (Continuation<? super Unit>) (Continuation<?>) NO_SUSPEND);
            assertEquals("p2", backend.lastPlayerUrl);
        } finally {
            http.close();
        }
    }
}
