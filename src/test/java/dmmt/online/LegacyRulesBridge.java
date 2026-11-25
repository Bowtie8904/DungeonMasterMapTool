package dmmt.online;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Test-only access to package-private legacy rules so the parity harness can use them as the baseline. */
public final class LegacyRulesBridge {
    private LegacyRulesBridge() {}

    public static int spellcastTotal(ObjectNode sheet, int fear) {
        return CharacterSheetRules.spellcastTotal(sheet, fear);
    }
}
