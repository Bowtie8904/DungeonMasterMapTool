package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.audio.AudioEngine;
import dmmt.audio.AudioLibraryService;
import dmmt.audio.AudioTrack;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Describes what the DM's audio engine is playing so browsers can play the same files themselves (3.41.5a).
 * Only original library files are referenced, together with the per-track loudness-matching gain in decibels
 * (relative to the original file) that the host would otherwise bake into its prepared playback copy.
 */
public final class OnlineAudioSync {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double MAX_GAIN_DB = 24;
    private static final double MIN_GAIN_DB = -48;

    private final AudioEngine engine;
    private final AudioLibraryService library;
    private final AssetRegistry assets;
    private String lastSignature;
    private long lastSentAt;

    public OnlineAudioSync(AudioEngine engine, AudioLibraryService library, AssetRegistry assets) {
        this.engine = engine;
        this.library = library;
        this.assets = assets;
    }

    /** Clamps a track gain to a range the browser can apply safely. */
    static double clampGainDb(double gainDb) {
        if (Double.isNaN(gainDb) || Double.isInfinite(gainDb)) {
            return 0;
        }
        return Math.max(MIN_GAIN_DB, Math.min(MAX_GAIN_DB, gainDb));
    }

    /** Builds the audio message body for the current engine state. Must run on the JavaFX thread. */
    public ObjectNode build(long serverTimeMs) {
        AudioEngine.State state = engine.snapshot();
        ObjectNode out = MAPPER.createObjectNode();
        out.put("serverTime", serverTimeMs);
        out.put("panic", state.panic());
        out.put("effectsPaused", state.effectsPaused());
        ObjectNode music = trackNode(state.trackId());
        if (music != null) {
            music.put("playing", state.musicPlaying() && !state.musicPaused());
            music.put("positionMs", state.positionMs());
            music.put("loop", state.musicLoop());
            out.set("music", music);
        } else {
            out.putNull("music");
        }
        ArrayNode effects = out.putArray("effects");
        Map<String, Integer> counts = new HashMap<>();
        for (String id : state.runningEffects()) {
            counts.merge(id, 1, Integer::sum);
        }
        counts.forEach((id, count) -> {
            ObjectNode node = trackNode(id);
            if (node != null) {
                node.put("count", count);
                node.put("loop", state.activeEffects().contains(id));
                effects.add(node);
            }
        });
        return out;
    }

    private ObjectNode trackNode(String trackId) {
        if (trackId == null) {
            return null;
        }
        AudioTrack track = library.track(trackId).orElse(null);
        if (track == null || !track.isReady()) {
            return null;
        }
        String url = assets.registerAudio(library.fileOf(track));
        if (url == null) {
            return null;
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", track.getId());
        node.put("name", track.getName());
        node.put("url", url);
        node.put("gainDb", clampGainDb(track.effectiveGainDb()));
        node.put("durationMs", track.getDurationMs());
        return node;
    }

    /**
     * Returns a message to broadcast when something other than the playback position changed, or when the periodic
     * resync is due; otherwise {@code null}.
     */
    public ObjectNode changeOrResync(long serverTimeMs, long resyncMs) {
        ObjectNode body = build(serverTimeMs);
        ObjectNode comparable = body.deepCopy();
        comparable.remove("serverTime");
        if (comparable.path("music").isObject()) {
            ((ObjectNode) comparable.get("music")).remove("positionMs");
        }
        String signature = comparable.toString();
        boolean changed = !Objects.equals(signature, lastSignature);
        if (!changed && serverTimeMs - lastSentAt < resyncMs) {
            return null;
        }
        lastSignature = signature;
        lastSentAt = serverTimeMs;
        return body;
    }

    /** Forces the next {@link #changeOrResync} call to emit. */
    public void invalidate() {
        lastSignature = null;
    }
}
