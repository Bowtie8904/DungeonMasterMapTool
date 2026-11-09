package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** DM-only values from saved player and testing character sheets; never part of a map snapshot. */
public record ActorTokenResources(int hp, int maxHp, int stress, int maxStress, Integer armor, Integer hope) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Map<String, ActorTokenResources> load(Campaign campaign, CampaignStore store) throws IOException {
        return new Reader().load(campaign, store);
    }

    /** Retains only derived resource values, never mutable character sheets. */
    public static final class Reader {
        private final Map<String, CachedResources> cache = new HashMap<>();
        private CampaignStore lastStore;
        private String lastCampaign;

        private record CachedResources(String json, ActorTokenResources resources) { }

        public Map<String, ActorTokenResources> load(Campaign campaign, CampaignStore store) throws IOException {
            String campaignId = campaign == null ? null : campaign.getId();
            if (store != lastStore || !java.util.Objects.equals(campaignId, lastCampaign))
                cache.clear();
            lastStore = store;
            lastCampaign = campaignId;
            Map<String, ActorTokenResources> values = loadValues(campaign, store);
            cache.keySet().retainAll(values.keySet());
            return values;
        }

        private Map<String, ActorTokenResources> loadValues(Campaign campaign, CampaignStore store) throws IOException {
            Map<String, ActorTokenResources> values = new HashMap<>();
            if (campaign == null || !"daggerheart".equalsIgnoreCase(campaign.getSystem()))
                return values;
            for (Campaign.Actor actor : campaign.getActors()) {
                if (!actor.isTestingCharacter() && campaign.player(actor.getOwnerId()) == null)
                    continue;
                String saved = store.loadSheet(campaign.getId(), actor.getId());
                if (saved == null)
                    continue;
                CachedResources previous = cache.get(actor.getId());
                if (previous != null && previous.json().equals(saved)) {
                    values.put(actor.getId(), previous.resources());
                    continue;
                }
                JsonNode sheet = MAPPER.readTree(saved);
                if (sheet == null || !sheet.isObject())
                    throw new IOException("Invalid sheet for " + actor.getName());
                if (!sheet.has("hp") || !sheet.has("stress"))
                    continue;
                int maxHp = trackValue(sheet.path("hp"), "slots", actor.getName());
                int markedHp = trackValue(sheet.path("hp"), "on", actor.getName());
                int maxStress = trackValue(sheet.path("stress"), "slots", actor.getName());
                int markedStress = trackValue(sheet.path("stress"), "on", actor.getName());
                if (maxHp == 0)
                    continue;
                Integer armor = sheet.has("armorTrack") ? Math.max(0,
                        trackValue(sheet.path("armorTrack"), "slots", actor.getName())
                                - trackValue(sheet.path("armorTrack"), "on", actor.getName())) : null;
                Integer hope = sheet.has("hope") ? trackValue(sheet.path("hope"), "on", actor.getName()) : null;
                ActorTokenResources resources = new ActorTokenResources(Math.min(markedHp, maxHp), maxHp,
                        Math.min(markedStress, maxStress), maxStress, armor, hope);
                cache.put(actor.getId(), new CachedResources(saved, resources));
                values.put(actor.getId(), resources);
            }
            return Map.copyOf(values);
        }
    }

    private static int trackValue(JsonNode track, String field, String name) throws IOException {
        JsonNode value = track.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
            throw new IOException("Invalid " + field + " resource value for " + name);
        return value.intValue();
    }
}
