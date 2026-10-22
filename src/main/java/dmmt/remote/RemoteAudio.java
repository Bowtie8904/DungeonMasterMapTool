package dmmt.remote;

import dmmt.log.Log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.audio.*;
import dmmt.service.AppSettings;
import dmmt.service.HostSettingsAuthority;
import dmmt.service.JsonMappers;
import dmmt.service.Tuning;
import dmmt.ui.AudioControls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Explicitly enabled host audio operations. Audio bytes never form part of the replicated library. */
public final class RemoteAudio implements RemoteOperations.Endpoint, AutoCloseable {
    private static final Log LOG = Log.of(RemoteAudio.class);
    private static final ObjectMapper JSON = JsonMappers.create();
    private static final int PAGE = 96;
    private final Supplier<AudioLibraryService> libraries;
    private final Supplier<AudioEngine> engines;
    private final RemoteAssetStore assets;
    private final Path cache;
    private final Executor worker;
    private final Executor owner;
    private final Consumer<String> status;
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private long revision;
    private long librarySeen = -1;
    private long engineSeen = -1;
    private AudioLibraryService librarySeenInstance;
    private AudioEngine engineSeenInstance;
    private boolean closed;
    private Runnable libraryChanged = () -> { };
    private long catalogRevision;
    private String catalogSignature = "";
    private static final Set<String> SETTING_ACTIONS =
            Set.of("masterVolume", "musicVolume", "effectsVolume", "volumes", "shuffle");
    private HostSettingsAuthority settingsAuthority;
    private AppSettings hostSettings;
    private CompletableFuture<Void> settingsTail = CompletableFuture.completedFuture(null);
    /** Latest engine value per key whose host persistence is still chained. */
    private final Map<String, String> pendingSettings = new HashMap<>();
    /** Host value each pending key chain expects; advanced only by this endpoint's accepted writes. */
    private final Map<String, String> settingsBase = new HashMap<>();

    private long catalogRevision() {
        AudioLibraryService library = libraries.get();
        String signature = library == null ? "disabled" : library.revision() + ":"
                + library.preparationQueue().items().stream()
                .map(item -> item.trackId() + ":" + item.stage() + ":" + Math.round(item.progress() * 100))
                .collect(java.util.stream.Collectors.joining("|"));
        if (library != librarySeenInstance) signature += ":" + System.identityHashCode(library);
        if (!catalogSignature.equals(signature)) {
            catalogSignature = signature;
            catalogRevision++;
        }
        return catalogRevision;
    }

    public record Clip(String name, long startMs, long endMs) {
        public Clip {
            text(name, 240);
            if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("Invalid clip range.");
        }
    }

    public record JobState(String id, String action, String state, double progress, String error, JsonNode result) { }
    public record State(boolean enabled, long revision, long libraryRevision, AudioEngine.State playback,
                        List<JobState> jobs, boolean restartRequired) { }
    public record Catalog(long revision, int next, List<AudioCategory> categories, List<AudioTrack> tracks,
                          List<AudioPreparationQueue.RemoteItem> preparation) { }

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final String action;
        final JsonNode arguments;
        final AudioLibraryService library;
        String state = "QUEUED";
        double progress;
        String error;
        JsonNode result = NullNode.getInstance();
        FutureTask<?> task;
        volatile int completedClips;
        volatile boolean executing;
        Job(String action, JsonNode arguments, AudioLibraryService library) {
            this.action = action;
            this.arguments = arguments.deepCopy();
            this.library = library;
        }
        JobState snapshot() { return new JobState(id, action, state, progress, error, result); }
    }

    public RemoteAudio(Supplier<AudioControls> controls, RemoteAssetStore assets, Path cache,
                       Executor worker, Executor owner, Consumer<String> status) {
        this(() -> controls.get() == null ? null : controls.get().library(),
                () -> controls.get() == null ? null : controls.get().engine(), assets, cache, worker, owner, status);
        libraryChanged = () -> { if (controls.get() != null) controls.get().refreshLibraryChoices(); };
    }

    public RemoteAudio(Supplier<AudioLibraryService> libraries, Supplier<AudioEngine> engines,
                       RemoteAssetStore assets, Path cache, Executor worker, Executor owner, Consumer<String> status) {
        this.libraries = Objects.requireNonNull(libraries);
        this.engines = Objects.requireNonNull(engines);
        this.assets = Objects.requireNonNull(assets);
        this.cache = cache.toAbsolutePath().normalize();
        this.worker = Objects.requireNonNull(worker);
        this.owner = Objects.requireNonNull(owner);
        this.status = status == null ? ignored -> { } : status;
    }

    public RemoteOperations.Endpoint endpoint() { return this; }

    private AudioLibraryService library() {
        AudioLibraryService library = libraries.get();
        if (closed || library == null || engines.get() == null)
            throw new IllegalStateException("Host audio is disabled.");
        return library;
    }

    private long revision() {
        AudioLibraryService library = libraries.get();
        AudioEngine engine = engines.get();
        long l = library == null ? -1 : library.revision();
        long e = engine == null ? -1 : engine.revision();
        if (library != librarySeenInstance || engine != engineSeenInstance || l != librarySeen || e != engineSeen) {
            revision++;
            librarySeen = l;
            engineSeen = e;
            librarySeenInstance = library;
            engineSeenInstance = engine;
        }
        return revision;
    }

    private State state() {
        long current = revision();
        boolean enabled = libraries.get() != null && engines.get() != null;
        return new State(enabled, current, catalogRevision(),
                enabled ? engines.get().snapshot() : null,
                jobs.values().stream().map(Job::snapshot).toList(), enabled && folderRestartRequired());
    }

    /**
     * audio.folder is restart-only: the live library keeps serving its opened folder until A restarts. Report a
     * pending folder switch (never its path) instead of silently implying the new folder is active.
     */
    private boolean folderRestartRequired() {
        AppSettings settings = hostSettings;
        AudioLibraryService library = libraries.get();
        if (settings == null || library == null || library.root() == null) return false;
        try {
            Path configured = AppSettings.resolveConfiguredFolder(settings.getFile(),
                    settings.get(Tuning.AUDIO_FOLDER.key(), Tuning.AUDIO_FOLDER.defaultValue()), "dmmap-audio");
            return !configured.toAbsolutePath().normalize().equals(library.root().toAbsolutePath().normalize());
        } catch (RuntimeException invalid) {
            return true;
        }
    }

    @Override public CompletableFuture<JsonNode> query(String action, JsonNode payload) {
        try {
            if (closed) throw new IllegalStateException("Host audio session stopped.");
            return switch (action) {
                case "state" -> CompletableFuture.completedFuture(JSON.valueToTree(state()));
                case "catalog" -> {
                    AudioLibraryService library = library();
                    int offset = payload.path("offset").asInt(-1);
                    if (offset < 0 || offset > 100000) throw new IllegalArgumentException("Invalid audio page.");
                    List<AudioCategory> categories = library.categories();
                    List<AudioTrack> tracks = library.tracks();
                    List<AudioPreparationQueue.Item> tasks = library.preparationQueue().items();
                    int size = Math.max(categories.size(), Math.max(tracks.size(), tasks.size()));
                    int end = Math.min(size, offset + PAGE);
                    if (offset > size) throw new IllegalArgumentException("Audio catalog changed; refresh it.");
                    var safeTracks = tracks.subList(Math.min(offset, tracks.size()), Math.min(end, tracks.size()))
                            .stream().map(RemoteAudio::safeTrack).toList();
                    var safeTasks = tasks.subList(Math.min(offset, tasks.size()), Math.min(end, tasks.size()))
                            .stream().map(item -> new AudioPreparationQueue.RemoteItem(
                                    safeName(item.source().getFileName().toString()), item.trackId(),
                                    item.stage(), Math.max(0, Math.min(1, item.progress())),
                                    item.error() == null ? null : "Preparation failed or cancelled; retry on the host.")).toList();
                    yield CompletableFuture.completedFuture(JSON.valueToTree(new Catalog(catalogRevision(),
                            end == size ? -1 : end,
                            List.copyOf(categories.subList(Math.min(offset, categories.size()), Math.min(end, categories.size()))),
                            safeTracks, safeTasks)));
                }
                default -> CompletableFuture.failedFuture(new IllegalArgumentException("Unknown audio query."));
            };
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override public CompletableFuture<JsonNode> execute(String action, JsonNode payload) {
        try {
            AudioLibraryService library = library();
            if (!payload.path("revision").isIntegralNumber() || payload.path("revision").asLong() != revision())
                throw new IllegalStateException("Audio changed on A. Refreshed host state; review and retry.");
            JsonNode args = payload.path("arguments");
            if (!args.isArray() || args.size() > 8) throw new IllegalArgumentException("Invalid audio arguments.");
            LOG.info("Executing remote audio command '{}'{}", action, commandTarget(library, action, args));
            AudioEngine engine = engines.get();
            if (SETTING_ACTIONS.contains(action) && settingsAuthority == null)
                throw new IllegalStateException("Host audio settings are unavailable; nothing was changed.");
            JsonNode result = NullNode.getInstance();
            Map<String, String> settingsChanges = new LinkedHashMap<>();
            switch (action) {
                case "playCategory" -> engine.playCategory(category(library, nullable(args, 0)));
                case "playTrack" -> engine.playTrack(track(library, args, 0).getId());
                case "toggleMusic" -> engine.toggleMusic();
                case "next" -> engine.next();
                case "previous" -> engine.previous();
                case "stopMusic" -> engine.stopMusic();
                case "toggleAll" -> engine.toggleAll();
                case "effectActive" -> engine.setEffectActive(track(library, args, 0).getId(), bool(args, 1));
                case "toggleEffect" -> engine.toggleEffect(track(library, args, 0).getId());
                case "stopEffectsForTrack" -> engine.stopEffectsForTrack(track(library, args, 0).getId());
                case "effectsPaused" -> engine.setEffectsPaused(bool(args, 0));
                case "stopAllEffects" -> engine.stopAllEffects();
                case "musicLoop" -> engine.setMusicLoop(bool(args, 0));
                case "panic" -> engine.setPanic(bool(args, 0));
                case "togglePanic" -> engine.togglePanic();
                case "masterVolume" -> { engine.setMasterVolume(volume(args, 0)); settingsChanges.put("audio.masterVolume", Double.toString(engine.masterVolume())); }
                case "musicVolume" -> { engine.setMusicVolume(volume(args, 0)); settingsChanges.put("audio.musicVolume", Double.toString(engine.musicVolume())); }
                case "effectsVolume" -> { engine.setEffectsVolume(volume(args, 0)); settingsChanges.put("audio.effectsVolume", Double.toString(engine.effectsVolume())); }
                case "volumes" -> {
                    engine.setVolumes(volume(args, 0), volume(args, 1), volume(args, 2));
                    settingsChanges.put("audio.masterVolume", Double.toString(engine.masterVolume()));
                    settingsChanges.put("audio.musicVolume", Double.toString(engine.musicVolume()));
                    settingsChanges.put("audio.effectsVolume", Double.toString(engine.effectsVolume()));
                }
                case "shuffle" -> {
                    boolean shuffle = bool(args, 0);
                    engine.setShuffle(shuffle);
                    settingsChanges.put("audio.shuffle", Boolean.toString(shuffle));
                }
                case "reopenTrack" -> engine.reopenTrack(track(library, args, 0).getId());
                case "cancelJob" -> {
                    Job job = job(args);
                    if (job.task != null && !terminal(job.state)) {
                        job.state = "CANCELLED";
                        job.task.cancel(true);
                    }
                }
                case "retryJob" -> {
                    Job previous = job(args);
                    if (!terminal(previous.state) || "READY".equals(previous.state) || previous.executing)
                        throw new IllegalStateException("This audio job cannot be retried.");
                    result = startJob(previous.action, previous.arguments, library, previous.completedClips);
                }
                case "waveform", "cut", "analyze", "gain", "import", "playbackFile" -> {
                    validateJob(action, args, library);
                    result = startJob(action, args, library);
                }
                case "cancelPreparation", "retryFailed", "retry", "prioritize",
                     "createCategory", "renameCategory", "styleCategory", "deleteCategory", "categoryHidden",
                     "trackHidden", "styleTrack", "renameTrack", "effectLooping", "moveTrack", "changeKind",
                     "deleteTrack" -> {
                    // Persistence/deletion never blocks the application thread. Check revision again on the worker
                    // under the same library lock as the mutation, so an intervening host edit wins.
                    long expected = library.revision();
                    CompletableFuture<JsonNode> future = new CompletableFuture<>();
                    worker.execute(() -> {
                        try {
                            if (Set.of("cancelPreparation", "retryFailed", "retry", "prioritize").contains(action)) {
                                if (library.revision() != expected)
                                    throw new IllegalStateException("Audio changed on A; refresh and retry.");
                                mutate(action, args, library);
                            } else {
                                synchronized (library) {
                                    if (library.revision() != expected)
                                        throw new IllegalStateException("Audio changed on A; refresh and retry.");
                                    mutate(action, args, library);
                                }
                            }
                            owner.execute(() -> {
                                engine.refreshEffectLooping();
                                libraryChanged.run();
                                if ("deleteTrack".equals(action)) {
                                    engine.stopEffectsForTrack(nullable(args, 0));
                                    if (Objects.equals(engine.currentTrackId(), nullable(args, 0)))
                                        engine.stopMusic();
                                }
                                future.complete(response(NullNode.getInstance()));
                            });
                        } catch (Exception failure) {
                            owner.execute(() -> future.completeExceptionally(safeFailure(failure)));
                        }
                    });
                    return future;
                }
                default -> throw new IllegalArgumentException("Unknown audio command.");
            }
            if (!settingsChanges.isEmpty()) return persistSettings(engine, settingsChanges);
            return CompletableFuture.completedFuture(response(result));
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(safeFailure(failure));
        }
    }

    private static final Set<String> TRACK_ACTIONS = Set.of("playTrack", "effectActive", "toggleEffect",
            "stopEffectsForTrack", "reopenTrack", "trackHidden", "styleTrack", "renameTrack", "effectLooping",
            "moveTrack", "changeKind", "deleteTrack", "waveform", "playbackFile");

    private static String commandTarget(AudioLibraryService library, String action, JsonNode args) {
        if (!TRACK_ACTIONS.contains(action) || !args.path(0).isTextual()) return "";
        return " for track " + library.trackLabel(args.path(0).asText());
    }

    private JsonNode response(JsonNode result) {
        ObjectNode response = JSON.createObjectNode();
        response.set("state", JSON.valueToTree(state()));
        response.set("result", result);
        return response;
    }

    private void mutate(String action, JsonNode a, AudioLibraryService l) throws IOException {
        switch (action) {
            case "createCategory" -> l.createCategory(required(a, 0), style(a, 1, true), style(a, 2, false));
            case "renameCategory" -> l.renameCategory(category(l, required(a, 0)), required(a, 1));
            case "styleCategory" -> l.styleCategory(category(l, required(a, 0)), style(a, 1, true), style(a, 2, false));
            case "deleteCategory" -> l.deleteCategory(category(l, required(a, 0)));
            case "categoryHidden" -> l.setCategoryHidden(category(l, required(a, 0)), bool(a, 1));
            case "trackHidden" -> l.setTrackHidden(track(l, a, 0).getId(), bool(a, 1));
            case "styleTrack" -> l.styleTrack(track(l, a, 0).getId(), style(a, 1, true), style(a, 2, false));
            case "renameTrack" -> l.renameTrack(track(l, a, 0).getId(), required(a, 1));
            case "effectLooping" -> l.setEffectLooping(track(l, a, 0).getId(), bool(a, 1));
            case "moveTrack" -> l.moveTrack(track(l, a, 0).getId(), category(l, required(a, 1)));
            case "changeKind" -> l.changeKind(track(l, a, 0).getId(), kind(a, 1), category(l, nullable(a, 2)));
            case "deleteTrack" -> l.deleteTrack(track(l, a, 0).getId());
            case "cancelPreparation" -> l.preparationQueue().cancelRemaining();
            case "retryFailed" -> l.preparationQueue().retryFailed();
            case "retry" -> l.preparationQueue().retry(track(l, a, 0).getId());
            case "prioritize" -> l.preparationQueue().prioritize(track(l, a, 0).getId());
            default -> throw new IllegalArgumentException("Unknown audio edit.");
        }
    }

    private void validateJob(String action, JsonNode a, AudioLibraryService l) throws IOException {
        if ("import".equals(action)) {
            JSON.treeToValue(a.path(0), RemoteAssetStore.AssetDescriptor.class);
            String name = required(a, 1);
            if (!name.equals(safeName(name)) || !AudioFormats.isSupported(Path.of(name)))
                throw new IllegalArgumentException("Choose an MP3 or WAV file.");
            kind(a, 2);
            category(l, nullable(a, 3));
            if (!a.path(4).isNull()) required(a, 4);
            return;
        }
        AudioTrack track = track(l, a, 0);
        if (!track.isReady()) throw new IllegalArgumentException("Audio is still being prepared.");
        switch (action) {
            case "waveform" -> {
                int frame = a.path(1).asInt(-1);
                if (frame < 10 || frame > 2000) throw new IllegalArgumentException("Invalid waveform resolution.");
            }
            case "gain" -> {
                if (!a.path(1).isNull()) {
                    double gain = number(a, 1);
                    if (gain < -60 || gain > 24) throw new IllegalArgumentException("Invalid gain.");
                }
            }
            case "cut" -> {
                kind(a, 1);
                category(l, nullable(a, 2));
                JsonNode cuts = a.path(3);
                if (!cuts.isArray() || cuts.isEmpty() || cuts.size() > 128)
                    throw new IllegalArgumentException("Choose between 1 and 128 clips.");
                for (JsonNode value : cuts) {
                    Clip clip = JSON.treeToValue(value, Clip.class);
                    if (clip.endMs() > track.getDurationMs()) throw new IllegalArgumentException("Clip exceeds the source duration.");
                }
            }
            case "analyze", "playbackFile" -> { }
            default -> throw new IllegalArgumentException("Unknown audio preparation.");
        }
    }

    private JsonNode startJob(String action, JsonNode arguments, AudioLibraryService library) {
        return startJob(action, arguments, library, 0);
    }

    private JsonNode startJob(String action, JsonNode arguments, AudioLibraryService library, int completedClips) {
        if (jobs.size() >= 64) {
            var terminal = jobs.values().stream().filter(job -> terminal(job.state)).findFirst();
            if (terminal.isEmpty()) throw new IllegalStateException("The host audio job queue is full.");
            jobs.remove(terminal.get().id);
        }
        Job job = new Job(action, arguments, library);
        job.completedClips = completedClips;
        jobs.put(job.id, job);
        job.task = new FutureTask<>(() -> {
            job.executing = true;
            owner.execute(() -> { if (!closed && !"CANCELLED".equals(job.state)) job.state = "RUNNING"; });
            try {
                JsonNode result = runJob(job);
                owner.execute(() -> {
                    if (closed || "CANCELLED".equals(job.state)) return;
                    job.result = result;
                    job.progress = 1;
                    job.state = "READY";
                    if (engines.get() != null) engines.get().refreshTrackVolumes();
                    libraryChanged.run();
                    status.accept("Host audio " + action + " completed.");
                });
            } catch (Exception failure) {
                owner.execute(() -> {
                    if (closed || "CANCELLED".equals(job.state)) return;
                    job.error = safeFailure(failure).getMessage();
                    job.state = "FAILED";
                    status.accept(job.error);
                });
            } finally {
                job.executing = false;
            }
            return null;
        });
        worker.execute(job.task);
        return JSON.createObjectNode().put("jobId", job.id);
    }

    private JsonNode runJob(Job job) throws IOException {
        JsonNode a = job.arguments;
        AudioLibraryService l = job.library;
        Files.createDirectories(cache);
        if ("import".equals(job.action)) {
            var asset = JSON.treeToValue(a.path(0), RemoteAssetStore.AssetDescriptor.class);
            Path folder = cache.resolve(job.id);
            Files.createDirectories(folder);
            Path source = folder.resolve(required(a, 1));
            if (!assets.descriptor(asset.sha256()).equals(asset)) throw new IOException("Invalid uploaded audio.");
            Path materialized = assets.materialize(asset.sha256(), folder, AudioFormats.extensionOf(source));
            if (!materialized.equals(source)) Files.move(materialized, source, StandardCopyOption.REPLACE_EXISTING);
            // Queue owns the staged source until copied; it also retains failed copies for retry.
            AudioPreparationQueue queue = l.preparationQueue();
            AudioPreparationQueue.Item item = queue.enqueue(source, kind(a, 2), nullable(a, 3), nullable(a, 4));
            java.util.concurrent.atomic.AtomicBoolean cleaning = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable[] cleanup = new Runnable[1];
            cleanup[0] = () -> {
                if (item.trackId() == null || !cleaning.compareAndSet(false, true)) return;
                queue.removeListener(cleanup[0]);
                worker.execute(() -> {
                    try {
                        Files.deleteIfExists(source);
                        Files.deleteIfExists(folder);
                    } catch (IOException failure) {
                        LOG.warn("Could not remove copied remote audio staging.", failure);
                    }
                });
            };
            queue.addListener(cleanup[0]);
            cleanup[0].run();
            return NullNode.getInstance();
        }
        AudioTrack track = track(l, a, 0);
        return switch (job.action) {
            case "waveform" -> {
                int frame = a.path(1).asInt();
                Path file = cache.resolve(job.id + ".peaks");
                WaveformPeaks peaks = WaveformPeaks.readCache(l.peaksFileOf(track), frame);
                if (peaks == null) peaks = WaveformPeaks.analyze(l.fileOf(track), frame,
                        progress -> owner.execute(() -> job.progress = progress));
                peaks.writeCache(file);
                try { yield JSON.valueToTree(assets.publishRequiredAsset(file)); }
                finally { Files.deleteIfExists(file); }
            }
            case "playbackFile" -> {
                // Exception to host-only audio: the editor on B previews the file locally. Only the file the host
                // would play for this track is published; live music and effects never leave A.
                AudioLibraryService.PlaybackSource source = l.playbackSourceOf(track);
                ObjectNode result = JSON.createObjectNode();
                result.set("asset", JSON.valueToTree(assets.publishRequiredAsset(source.file())));
                result.put("extension", AudioFormats.extensionOf(source.file()));
                result.put("bakedGainDb", source.bakedGainDb());
                yield result;
            }
            case "analyze" -> { l.analyzeLoudness(track.getId()); yield NullNode.getInstance(); }
            case "gain" -> {
                l.setGainOverride(track.getId(), a.path(1).isNull() ? null : number(a, 1));
                yield NullNode.getInstance();
            }
            case "cut" -> {
                int done = 0;
                for (JsonNode value : a.path(3)) {
                    if (done++ < job.completedClips) continue;
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Audio job cancelled.");
                    Clip clip = JSON.treeToValue(value, Clip.class);
                    Path source = l.fileOf(track);
                    Path target = l.reserveClipFile(clip.name(), AudioFormats.extensionOf(source));
                    try {
                        var cut = AudioClipCutter.cut(source, clip.startMs(), clip.endMs(), target);
                        l.addClip(target, clip.name(), kind(a, 1), nullable(a, 2), track, cut.startMs(), cut.endMs());
                    } catch (Exception failure) {
                        Files.deleteIfExists(target);
                        throw failure;
                    }
                    job.completedClips = done;
                    double progress = done / (double) a.path(3).size();
                    owner.execute(() -> job.progress = progress);
                }
                yield NullNode.getInstance();
            }
            default -> throw new IllegalArgumentException("Unknown audio job.");
        };
    }

    private Job job(JsonNode args) {
        Job job = jobs.get(required(args, 0));
        if (job == null) throw new IllegalArgumentException("Unknown audio job.");
        return job;
    }

    private static boolean terminal(String state) { return Set.of("READY", "FAILED", "CANCELLED").contains(state); }
    private static AudioTrack track(AudioLibraryService library, JsonNode args, int index) {
        return library.track(required(args, index)).orElseThrow(() -> new IllegalArgumentException("Unknown audio track."));
    }
    private static String category(AudioLibraryService library, String id) {
        if (id == null) return null;
        return library.category(id).orElseThrow(() -> new IllegalArgumentException("Unknown audio category.")).getId();
    }
    private static AudioKind kind(JsonNode a, int i) {
        return AudioKind.valueOf(required(a, i).toUpperCase(Locale.ROOT));
    }
    private static String nullable(JsonNode a, int i) { return a.path(i).isNull() ? null : required(a, i); }
    private static String required(JsonNode a, int i) {
        JsonNode node = a.path(i);
        if (!node.isTextual()) throw new IllegalArgumentException("Missing audio argument.");
        String value = node.asText();
        text(value, 240);
        return value;
    }
    private static void text(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid audio text.");
    }
    private static String style(JsonNode a, int i, boolean color) {
        String value = nullable(a, i);
        if (value != null && !(color ? value.matches("#[0-9a-fA-F]{6}") : value.matches("[a-zA-Z0-9_-]{1,100}")))
            throw new IllegalArgumentException("Invalid audio style.");
        return value;
    }
    private static boolean bool(JsonNode a, int i) {
        if (!a.path(i).isBoolean()) throw new IllegalArgumentException("Missing audio flag.");
        return a.path(i).asBoolean();
    }
    private static double number(JsonNode a, int i) {
        if (!a.path(i).isNumber() || !Double.isFinite(a.path(i).asDouble()))
            throw new IllegalArgumentException("Invalid audio value.");
        return a.path(i).asDouble();
    }
    private static double volume(JsonNode a, int i) {
        double value = number(a, i);
        if (value < 0 || value > 1) throw new IllegalArgumentException("Invalid audio volume.");
        return value;
    }
    private static String safeName(String value) {
        String name = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return name.substring(0, Math.min(240, name.length()));
    }
    private static AudioTrack safeTrack(AudioTrack original) {
        AudioTrack track = JSON.convertValue(original, AudioTrack.class);
        track.setFile("");
        track.setPlaybackFile(null);
        track.setPeakSafePlaybackFile(null);
        track.setOriginalFileName(original.getOriginalFileName() == null ? null : safeName(original.getOriginalFileName()));
        if (track.getPreparationError() != null) track.setPreparationError("Preparation failed or cancelled; retry.");
        return track;
    }
    private static IOException safeFailure(Throwable failure) {
        // Codec and filesystem failures often contain absolute paths. Never return those to the remote workspace.
        if (failure instanceof IllegalArgumentException || failure instanceof IllegalStateException)
            return new IOException(failure.getMessage());
        LOG.warn("Host audio operation failed.", failure);
        return new IOException("Host audio operation failed. Inspect A, then retry.");
    }

    /**
     * Binds remote volume/shuffle persistence to A's live settings object. Call on the owner (application) thread;
     * the authority belongs to that thread. Passing null disables remote setting commands.
     */
    public void configureSettings(AppSettings settings) {
        if (settingsAuthority != null) settingsAuthority.close();
        settingsAuthority = settings == null || closed ? null : new HostSettingsAuthority(settings);
        hostSettings = settingsAuthority == null ? null : settings;
        pendingSettings.clear();
        settingsBase.clear();
    }

    private CompletableFuture<JsonNode> persistSettings(AudioEngine engine, Map<String, String> changes) {
        HostSettingsAuthority authority = settingsAuthority;
        Map<String, String> host = authority.stateInMemory().settings().values();
        changes.forEach((key, value) -> {
            if (!pendingSettings.containsKey(key)) settingsBase.put(key, host.get(key));
            pendingSettings.put(key, value);
        });
        CompletableFuture<JsonNode> completion = new CompletableFuture<>();
        CompletableFuture<Void> previous = settingsTail;
        CompletableFuture<Void> done = new CompletableFuture<>();
        settingsTail = done;
        List<Map.Entry<String, String>> writes = List.copyOf(changes.entrySet());
        previous.whenComplete((ignored, failure) -> owner.execute(() ->
                writeSetting(authority, engine, writes, 0, completion, done)));
        return completion;
    }

    private void writeSetting(HostSettingsAuthority authority, AudioEngine engine, List<Map.Entry<String, String>> writes,
                              int index, CompletableFuture<JsonNode> completion,
                              CompletableFuture<Void> done) {
        if (index == writes.size()) {
            settled(writes);
            done.complete(null);
            completion.complete(response(NullNode.getInstance()));
            return;
        }
        String key = writes.get(index).getKey();
        String value = writes.get(index).getValue();
        try {
            if (closed || authority != settingsAuthority)
                throw new IllegalStateException("Host audio settings closed; the setting was not saved.");
            String live = engineValue(engine, key);
            if (!value.equals(live)) {
                if (Objects.equals(pendingSettings.get(key), live)) {
                    // A later chained command owns this key and persists the newer engine value.
                    writeSetting(authority, engine, writes, index + 1, completion, done);
                    return;
                }
                conflict(key, value, engine, authority, writes, completion, done);
                return;
            }
            HostSettingsAuthority.State state = authority.stateInMemory();
            String current = state.settings().values().get(key);
            if (!Objects.equals(current, settingsBase.get(key))) {
                conflict(key, value, engine, authority, writes, completion, done);
                return;
            }
            var request = new HostSettingsAuthority.Request(state.sessionId(), state.nextSequence(),
                    new AppSettings.RemoteEdit(UUID.randomUUID(), state.settings().revision(), key, current, value));
            authority.executeAsync(request, owner, worker).whenComplete((outcome, failure) -> owner.execute(() -> {
                if (failure != null) {
                    failSettings(writes, completion, done, safeFailure(failure));
                } else if (outcome.status() == HostSettingsAuthority.Status.ACCEPTED) {
                    if (authority == settingsAuthority && pendingSettings.containsKey(key)) settingsBase.put(key, value);
                    writeSetting(authority, engine, writes, index + 1, completion, done);
                } else if (outcome.status() == HostSettingsAuthority.Status.CONFLICT) {
                    conflict(key, value, engine, authority, writes, completion, done);
                } else {
                    failSettings(writes, completion, done, new IOException(
                            outcome.status() == HostSettingsAuthority.Status.SAVE_FAILED
                                    ? "A could not save the audio setting; refresh and retry."
                                    : "Host audio settings must be refreshed; review and retry."));
                }
            }));
        } catch (RuntimeException failure) {
            failSettings(writes, completion, done, safeFailure(failure));
        }
    }

    /** A host-side edit won: keep A's persisted value and bring A's engine back in line with it. */
    private void conflict(String key, String value, AudioEngine engine, HostSettingsAuthority authority,
                          List<Map.Entry<String, String>> writes, CompletableFuture<JsonNode> completion,
                          CompletableFuture<Void> done) {
        try {
            if (!closed && authority == settingsAuthority && value.equals(engineValue(engine, key))
                    && value.equals(pendingSettings.get(key))) {
                applySetting(engine, key, authority.stateInMemory().settings().values().get(key));
            }
        } catch (RuntimeException ignored) {
            LOG.warn("Could not restore the host audio engine value for {}.", key, ignored);
            // Leave A's engine untouched when its persisted value is unusable.
        }
        failSettings(writes, completion, done, new IOException(
                "This audio setting changed on A (" + key + "); A's value was kept. Review and retry."));
    }

    private void failSettings(List<Map.Entry<String, String>> writes, CompletableFuture<JsonNode> completion,
                              CompletableFuture<Void> done, IOException failure) {
        settled(writes);
        done.complete(null);
        completion.completeExceptionally(failure);
    }

    private void settled(List<Map.Entry<String, String>> writes) {
        writes.forEach(entry -> {
            if (pendingSettings.remove(entry.getKey(), entry.getValue())) settingsBase.remove(entry.getKey());
        });
    }

    private static String engineValue(AudioEngine engine, String key) {
        return switch (key) {
            case "audio.masterVolume" -> Double.toString(engine.masterVolume());
            case "audio.musicVolume" -> Double.toString(engine.musicVolume());
            case "audio.effectsVolume" -> Double.toString(engine.effectsVolume());
            case "audio.shuffle" -> Boolean.toString(engine.snapshot().shuffle());
            default -> throw new IllegalArgumentException("Unknown audio setting.");
        };
    }

    private static void applySetting(AudioEngine engine, String key, String value) {
        if (value == null) return;
        switch (key) {
            case "audio.masterVolume" -> engine.setMasterVolume(Double.parseDouble(value.trim()));
            case "audio.musicVolume" -> engine.setMusicVolume(Double.parseDouble(value.trim()));
            case "audio.effectsVolume" -> engine.setEffectsVolume(Double.parseDouble(value.trim()));
            case "audio.shuffle" -> engine.setShuffle(Boolean.parseBoolean(value.trim()));
            default -> { }
        }
    }

    @Override public void close() {
        closed = true;
        if (settingsAuthority != null) {
            settingsAuthority.close();
            settingsAuthority = null;
        }
        hostSettings = null;
        pendingSettings.clear();
        settingsBase.clear();
        for (Job job : jobs.values()) if (!terminal(job.state) && job.task != null) job.task.cancel(true);
        jobs.clear();
    }

    /** Serial accepted-state adapter; queued edits bind to the last accepted revision, never local persistence. */
    public static final class Client implements AudioRemoteBackend, AutoCloseable {
        private final RemoteOperations.Connection connection;
        private final AudioLibraryService library;
        private final AudioEngine engine;
        private final Executor worker;
        private final Executor owner;
        private final Runnable changed;
        private final Consumer<String> status;
        private final ArrayDeque<Runnable> commands = new ArrayDeque<>();
        private final Set<CompletableFuture<JsonNode>> pendingCommands = new HashSet<>();
        private final Map<String, CompletableFuture<JsonNode>> waitingJobs = new HashMap<>();
        private State state;
        private long catalogRevision = -2;
        private boolean running;
        private boolean polling;
        private boolean catalogLoading;
        private boolean closed;
        private long lastPoll;
        private static final class EditRevision {
            long value;
            EditRevision(long value) { this.value = value; }
        }
        private final ThreadLocal<EditRevision> editRevision = new ThreadLocal<>();

        public Client(RemoteOperations.Connection connection, AudioLibraryService library, AudioEngine engine,
                      Executor worker, Executor owner, Runnable changed, Consumer<String> status) {
            this.connection = connection;
            this.library = library;
            this.engine = engine;
            this.worker = worker;
            this.owner = owner;
            this.changed = changed;
            this.status = status == null ? ignored -> { } : status;
        }

        public java.util.List<RemoteSessionClient.TransferProgress> transferProgress() {
            return connection.transferProgress();
        }

        public State state() { return state; }
        public long acceptedCatalogRevision() { return catalogRevision; }
        @Override public long revision() { return state == null ? -1 : state.revision(); }
        @Override public void atRevision(long revision, Runnable edit) {
            EditRevision previous = editRevision.get();
            editRevision.set(new EditRevision(revision));
            try { edit.run(); }
            finally {
                if (previous == null) editRevision.remove(); else editRevision.set(previous);
            }
        }
        @Override public CompletableFuture<JsonNode> commandAt(long revision, String action, Object... arguments) {
            EditRevision previous = editRevision.get();
            editRevision.set(new EditRevision(revision));
            try { return command(action, arguments); }
            finally {
                if (previous == null) editRevision.remove(); else editRevision.set(previous);
            }
        }

        public void tick() {
            if (closed || !connection.connected() || polling || running || System.nanoTime() - lastPoll < 250_000_000L) return;
            polling = true;
            lastPoll = System.nanoTime();
            connection.query("audio", "state", JSON.createObjectNode()).whenComplete((value, failure) -> {
                polling = false;
                if (closed) return;
                if (failure != null) { status.accept("Could not refresh host audio."); return; }
                accept(value);
                drain();
            });
        }

        private void accept(JsonNode value) {
            if (closed) return;
            try {
                boolean restartWasRequired = state != null && state.restartRequired();
                state = JSON.treeToValue(value, State.class);
                if (state.restartRequired() && !restartWasRequired)
                    status.accept("A's audio folder changed; it takes effect after A restarts. The current host library stays active.");
                if (state.enabled()) {
                    engine.acceptRemote(state.playback());
                    if (catalogRevision != state.libraryRevision() && !catalogLoading) loadCatalog();
                } else {
                    library.acceptRemote(List.of(), List.of(), List.of());
                    engine.acceptRemote(new AudioEngine.State(null, null, false, false, 0, 0,
                            List.of(), List.of(), false, false, false, true, 0.8, 0.7, 0.6));
                    catalogRevision = state.libraryRevision();
                    status.accept("Audio is disabled on A.");
                }
                resolveJobs();
                changed.run();
            } catch (IOException | RuntimeException failure) {
                status.accept("Invalid host audio state.");
            }
        }

        private void resolveJobs() {
            if (state == null || state.enabled() && (catalogLoading || catalogRevision != state.libraryRevision())) return;
            for (JobState job : state.jobs()) {
                    CompletableFuture<JsonNode> future = waitingJobs.get(job.id());
                    if (future == null || !terminal(job.state())) continue;
                    waitingJobs.remove(job.id());
                    if ("READY".equals(job.state())) future.complete(job.result());
                    else future.completeExceptionally(new IOException(job.error() == null ? "Host audio job cancelled." : job.error()));
            }
        }

        private void loadCatalog() {
            catalogLoading = true;
            loadPage(0, -1, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
        private void loadPage(int offset, long expected, List<AudioCategory> categories, List<AudioTrack> tracks,
                              List<AudioPreparationQueue.RemoteItem> tasks) {
            connection.query("audio", "catalog", JSON.createObjectNode().put("offset", offset))
                    .whenComplete((value, failure) -> {
                        if (closed) return;
                        if (failure != null) { LOG.warn("Could not refresh the host audio catalog.", failure); catalogLoading = false; status.accept("Could not refresh host audio library."); return; }
                        try {
                            Catalog page = JSON.treeToValue(value, Catalog.class);
                            if (expected != -1 && expected != page.revision()) {
                                catalogLoading = false;
                                return;
                            }
                            categories.addAll(page.categories());
                            tracks.addAll(page.tracks());
                            tasks.addAll(page.preparation());
                            if (page.next() >= 0) {
                                if (page.next() <= offset || categories.size() + tracks.size() + tasks.size() > 100000)
                                    throw new IOException("Invalid audio catalog page.");
                                loadPage(page.next(), page.revision(), categories, tracks, tasks);
                            } else {
                                catalogRevision = page.revision();
                                catalogLoading = false;
                                library.acceptRemote(categories, tracks, tasks);
                                changed.run();
                                resolveJobs();
                            }
                        } catch (IOException | RuntimeException invalid) {
                            LOG.warn("Invalid host audio catalog.", invalid);
                            catalogLoading = false;
                            status.accept("Invalid host audio catalog.");
                        }
                    });
        }

        @Override public CompletableFuture<JsonNode> command(String action, Object... arguments) {
            CompletableFuture<JsonNode> completion = new CompletableFuture<>();
            EditRevision expectedRevision = editRevision.get();
            // Gain/import callers may originate in an existing background Task. All transport work is marshalled.
            owner.execute(() -> {
                if (closed) { completion.completeExceptionally(new IOException("Remote audio disconnected.")); return; }
                if (commands.size() >= 128) {
                    status.accept("Too many pending host audio changes. Wait for A before retrying.");
                    completion.completeExceptionally(new IOException("The host audio command queue is full."));
                    return;
                }
                pendingCommands.add(completion);
                completion.whenComplete((ignored, failure) -> owner.execute(() -> pendingCommands.remove(completion)));
                commands.add(() -> {
                    ObjectNode body = JSON.createObjectNode().put("revision",
                            expectedRevision == null ? state.revision() : expectedRevision.value);
                    body.set("arguments", JSON.valueToTree(arguments));
                    connection.command("audio", action, body).whenComplete((reply, failure) -> {
                        running = false;
                        if (closed) { completion.completeExceptionally(new IOException("Remote audio disconnected.")); return; }
                        if (failure != null) {
                            LOG.warn("Host audio command '{}' failed: {}", action, RemoteSessionServer.safe(failure.getMessage()));
                            status.accept("Host audio action failed: " + failure.getMessage());
                            completion.completeExceptionally(failure);
                            commands.clear();
                            List.copyOf(pendingCommands).forEach(queued -> {
                                if (!waitingJobs.containsValue(queued)) queued.completeExceptionally(
                                        new IOException("Audio changed or an action failed on A. Review before retrying queued changes."));
                            });
                            lastPoll = 0;
                            tick();
                        } else {
                            accept(reply.path("state"));
                            if (expectedRevision != null) expectedRevision.value = state.revision();
                            JsonNode result = reply.path("result");
                            if (result.has("jobId")) {
                                waitingJobs.put(result.path("jobId").asText(), completion);
                                resolveJobs();
                            }
                            else completion.complete(result);
                        }
                        drain();
                    });
                });
                tick();
                drain();
            });
            return completion;
        }
        private void drain() {
            if (closed || running || state == null || !connection.connected() || commands.isEmpty()) return;
            running = true;
            commands.remove().run();
        }
        @Override public CompletableFuture<JsonNode> query(String action, Object... arguments) {
            return connection.query("audio", action, JSON.createObjectNode().set("arguments", JSON.valueToTree(arguments)));
        }
        @Override public CompletableFuture<JsonNode> upload(Path source, AudioKind kind, String categoryId, String folderCategory) {
            CompletableFuture<JsonNode> completion = new CompletableFuture<>();
            owner.execute(() -> {
                if (closed) { completion.completeExceptionally(new IOException("Remote audio disconnected.")); return; }
                status.accept("Uploading audio to A: " + source.getFileName());
                connection.upload(source).thenCompose(asset -> command("import", asset,
                        source.getFileName().toString(), kind == null ? AudioKind.MUSIC : kind, categoryId, folderCategory))
                        .whenComplete((value, failure) -> {
                            if (failure == null) completion.complete(value);
                            else { status.accept("Audio upload failed: " + failure.getMessage()); completion.completeExceptionally(failure); }
                        });
            });
            return completion;
        }
        /** Downloads the file the editor on B plays for a track; the cached copy is reused while its hash is unchanged. */
        public CompletableFuture<AudioLibraryService.PlaybackSource> playbackSource(String trackId) {
            return command("playbackFile", trackId).thenCompose(value -> {
                try {
                    var asset = JSON.treeToValue(value.path("asset"), RemoteAssetStore.AssetDescriptor.class);
                    String extension = value.path("extension").asText("");
                    double gain = value.path("bakedGainDb").asDouble(0);
                    if (!AudioFormats.isSupported(Path.of("a." + extension)))
                        return CompletableFuture.failedFuture(new IOException("Unsupported host audio format."));
                    String label = library.track(trackId).map(AudioTrack::getName).orElse(null);
                    return connection.download(asset, label).thenApplyAsync(file -> {
                        try {
                            Path target = file.resolveSibling(asset.sha256() + "." + extension);
                            if (!Files.isRegularFile(target) || Files.size(target) != asset.size())
                                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                            return new AudioLibraryService.PlaybackSource(target, gain);
                        } catch (IOException failure) {
                            throw new java.util.concurrent.CompletionException(failure);
                        }
                    }, worker).thenApplyAsync(java.util.function.Function.identity(), owner);
                } catch (IOException invalid) { return CompletableFuture.failedFuture(invalid); }
            });
        }
        @Override public CompletableFuture<WaveformPeaks> waveform(String trackId, int frameMs) {
            return command("waveform", trackId, frameMs).thenCompose(value -> {
                try {
                    return connection.download(JSON.treeToValue(value, RemoteAssetStore.AssetDescriptor.class),
                            "Waveform: " + library.track(trackId).map(AudioTrack::getName).orElse("track"));
                } catch (IOException invalid) { return CompletableFuture.failedFuture(invalid); }
            }).thenCompose(file -> {
                CompletableFuture<WaveformPeaks> read = new CompletableFuture<>();
                worker.execute(() -> {
                    WaveformPeaks peaks = WaveformPeaks.readCache(file, frameMs);
                    owner.execute(() -> {
                        if (peaks == null) read.completeExceptionally(new IOException("Invalid host waveform."));
                        else read.complete(peaks);
                    });
                });
                return read;
            });
        }
        @Override public void close() {
            closed = true;
            commands.clear();
            List.copyOf(pendingCommands).forEach(future -> future.completeExceptionally(new IOException("Remote audio disconnected.")));
            pendingCommands.clear();
            waitingJobs.values().forEach(future -> future.completeExceptionally(new IOException("Remote audio disconnected.")));
            waitingJobs.clear();
        }
    }
}
