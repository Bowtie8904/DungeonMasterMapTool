package dmmt.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DaggerheartAdversary {
    private String sourceId;
    private String name;
    private int tier;
    private String category;
    private String categoryDisplayText;
    private String description;
    private String motivesAndTactics;
    private int difficulty;
    private String damageThresholds;
    private int maxHp;
    private int maxStress;
    private String attackModifier;
    private String defaultAttackName;
    private String defaultAttackRange;
    private String defaultAttackDamage;
    @Builder.Default
    private List<String> experiences = new ArrayList<>();
    @Builder.Default
    private List<Feature> features = new ArrayList<>();
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Feature {
        private String name;
        private String classification;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CombatState {
        private int markedHp;
        private int markedStress;
        private int tokens;
        private boolean dead;
    }
}
