package dmmt.online;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A campaign: players, actors and control links; independent of projects and maps. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Campaign {
    public static final String DEFAULT_SYSTEM = "Daggerheart";
    /** Game systems offered when creating a campaign. */
    public static final List<String> SYSTEMS = List.of(DEFAULT_SYSTEM);
    public static final String DEFAULT_DM_COLOR = "#e53935";
    public static final List<String> PLAYER_COLORS = List.of(
            "#2979ff", "#00c853", "#ffd600", "#ff9100", "#aa00ff", "#00e5ff", "#ff4081", "#ffffff");

    /** Most Fear tokens the GM can hold; the browser overlay and DM overlay both show at most this many. */
    public static final int MAX_FEAR = 12;

    @Builder.Default
    private int schemaVersion = 1;
    @Builder.Default
    private String id = UUID.randomUUID().toString();
    private String name;
    /** Game system label; informational for now ({@link #DEFAULT_SYSTEM} is the only supported one). */
    @Builder.Default
    private String system = DEFAULT_SYSTEM;
    @Builder.Default
    private List<Player> players = new ArrayList<>();
    @Builder.Default
    private List<Actor> actors = new ArrayList<>();
    /** Actor folder paths ("a/b"), kept so empty folders survive. */
    @Builder.Default
    private List<String> actorFolders = new ArrayList<>();
    /** Individual regular tokens (companions, summons) a player may move besides their actors. */
    @Builder.Default
    private List<TokenLink> controlledTokens = new ArrayList<>();

    /** Fear tokens the GM currently holds, 0 to {@link #MAX_FEAR}; missing in older files means 0. */
    private int fear;

    @Builder.Default
    private String dmDomainCardView = "card";

    @Builder.Default
    private String dmColor = DEFAULT_DM_COLOR;

    @Builder.Default
    private List<Countdown> countdowns = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Countdown(String id, String name, int initialValue, int value) {
        public Countdown {
            if (!CampaignStore.safeId(id)) throw new IllegalArgumentException("Invalid countdown id.");
            if (name == null || name.isBlank() || name.trim().length() > 100
                    || name.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Countdown name must be 1-100 characters without control characters.");
            }
            if (initialValue < 0 || value < 0 || value > initialValue) {
                throw new IllegalArgumentException("Countdown values must be between 0 and the initial value.");
            }
            name = name.trim();
        }

        public Countdown step(int delta) {
            if (delta != -1 && delta != 1) throw new IllegalArgumentException("Step must be -1 or 1.");
            return new Countdown(id, name, initialValue, (int) Math.max(0, Math.min(initialValue, (long) value + delta)));
        }
    }

    public void setFear(int fear) {
        this.fear = Math.max(0, Math.min(MAX_FEAR, fear));
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Player {
        @Builder.Default
        private String id = UUID.randomUUID().toString();
        private String name;
        /** Retained for old campaign round-tripping; chat names are derived from enabled actors. */
        @Builder.Default
        private String chatIdentity = "";
        @Builder.Default
        private String domainCardView = "card";
        private String color;
        /** {@code argon2id$m$t$p$saltB64$hashB64}; empty until the DM sets a password. */
        private String passwordHash;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Actor {
        @Builder.Default
        private String id = UUID.randomUUID().toString();
        private String name;
        private String ownerId;
        /** Disabled actors remain assigned but do not participate in player actions or automation. */
        private boolean disabled;
        /** DM-created testing actor; never granted by browser sheet data. */
        private boolean testingCharacter;
        /** sha256 hex of the re-encoded PNG in {@code artwork/}; {@code null} when none. */
        private String artwork;
        private ActorArtwork tokenArtwork;
        /** Folder path ("a/b") in the DM's actor tree; empty or {@code null} for the root. */
        private String folder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ActorArtwork {
        private String source;
        private String color;
        @Builder.Default
        private double sizeTiles = 1;
        @Builder.Default
        private double zoom = 1;
        @Builder.Default
        private double centerX = .5;
        @Builder.Default
        private double centerY = .5;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenLink {
        private String playerId;
        private String mapId;
        private String tokenId;
    }

    public Player player(String playerId) {
        return playerId == null ? null : players.stream().filter(p -> playerId.equals(p.getId())).findFirst().orElse(null);
    }

    public Actor actor(String actorId) {
        return actorId == null ? null : actors.stream().filter(a -> actorId.equals(a.getId())).findFirst().orElse(null);
    }

    public boolean ensureAccountColors() {
        boolean changed = false;
        if (dmColor == null || dmColor.isBlank()) {
            dmColor = DEFAULT_DM_COLOR;
            changed = true;
        }
        for (Player player : players) {
            if (player.getColor() != null && !player.getColor().isBlank()) continue;
            String next = PLAYER_COLORS.stream().min(java.util.Comparator.comparingLong(color ->
                    players.stream().filter(p -> color.equalsIgnoreCase(p.getColor())).count()
                            + (color.equalsIgnoreCase(dmColor) ? 1 : 0))).orElseThrow();
            player.setColor(next);
            changed = true;
        }
        return changed;
    }
}
