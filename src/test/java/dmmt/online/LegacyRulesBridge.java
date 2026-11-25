package dmmt.online;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Test-only access to package-private legacy rules so the parity harness can use them as the baseline. */
public final class LegacyRulesBridge {
    private LegacyRulesBridge() {}

    public static int spellcastTotal(ObjectNode sheet, int fear) {
        return CharacterSheetRules.spellcastTotal(sheet, fear);
    }

    public static void synchronizeDowntime(java.util.Map<String, ObjectNode> sheets) {
        WarriorAutomation.synchronizeDowntime(sheets);
    }

    public static java.util.List<com.fasterxml.jackson.databind.JsonNode> beastformCatalog(com.fasterxml.jackson.databind.JsonNode classes) {
        return BeastformRules.catalog(classes);
    }

    /** The legacy Beastform effect for a choice, or null when the legacy rules reject it. */
    public static ObjectNode beastformBuild(int level, com.fasterxml.jackson.databind.JsonNode classes, com.fasterxml.jackson.databind.JsonNode choice, boolean evolution, boolean anyTier) {
        try {
            return BeastformRules.build(level, BeastformRules.catalog(classes), choice, evolution, anyTier);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
