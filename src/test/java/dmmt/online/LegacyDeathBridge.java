package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Random;

/** Test-only access to the legacy death move rules so the parity harness can use them as the baseline. */
public final class LegacyDeathBridge {
    private LegacyDeathBridge() {}

    /** Takes a death move on the sheet (mutating it) and returns the legacy result as plain JSON. */
    public static ObjectNode take(ObjectNode sheet, ObjectNode request, String key, String name, JsonNode automation,
                                  JsonNode entry, Random random) {
        DeathMoves.Source source = new DeathMoves.Source(key, name, automation, entry);
        DeathMoves.Result result = DeathMoves.take(sheet, request, List.of(source), random);
        ObjectNode out = sheet.objectNode().put("title", result.title()).put("consequence", result.consequence());
        if (result.dice() != null) {
            out.put("total", result.dice().total());
            ArrayNode rolls = out.putArray("rolls");
            result.dice().rolls().forEach(rolls::add);
        }
        if (result.deathResult() != null) out.set("deathResult", result.deathResult());
        return out;
    }

    /** The legacy option list (move choices and Hope bonuses with their blockers). */
    public static ObjectNode options(ObjectNode sheet, String key, String name, JsonNode automation, JsonNode entry) {
        return DeathMoves.options(sheet, List.of(new DeathMoves.Source(key, name, automation, entry)));
    }

    public static void recover(ObjectNode sheet, ObjectNode roll, JsonNode request, boolean undying) {
        DeathMoves.recover(sheet, roll, request, undying);
    }
}
