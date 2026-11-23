package dmmt.online;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampaignStoreTest {
    @TempDir
    Path dir;

    @Test
    void automationCommitChecksEveryReadSheetEvenWhenItsAutomationRevisionDidNotChange() throws Exception {
        var store = new CampaignStore(dir);
        String id = store.create("Party").getId();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String actor : List.of("caster", "ally"))
            store.saveSheet(id, actor, "{\"notes\":\"old\",\"automationState\":{\"revision\":4}}");
        var originals = java.util.Map.of("caster", store.loadSheet(id, "caster"), "ally", store.loadSheet(id, "ally"));
        var changed = java.util.Map.of("caster", mapper.createObjectNode().put("notes", "cast"));
        var reports = List.of(mapper.createObjectNode().put("id", 1).put("ts", 10).put("text", "Cast"));
        store.saveSheet(id, "ally", "{\"notes\":\"edited\",\"automationState\":{\"revision\":4}}");
        int fear = store.load(id).getFear();
        assertThrows(CampaignStore.StaleAutomationSheet.class,
                () -> store.saveAutomationResults(id, originals, changed, reports, fear + 1, null));
        assertEquals(originals.get("caster"), store.loadSheet(id, "caster"));
        assertEquals("edited", mapper.readTree(store.loadSheet(id, "ally")).path("notes").asText());
        assertEquals(fear, store.load(id).getFear());
        assertTrue(store.historyTail(id, 10).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> store.saveAutomationResults(id, java.util.Map.of(), changed, reports, null, null));
        store.saveAutomationResults(id, java.util.Map.of("caster", originals.get("caster"), "ally", store.loadSheet(id, "ally")),
                changed, reports, fear + 1, null);
        assertEquals("cast", mapper.readTree(store.loadSheet(id, "caster")).path("notes").asText());
        assertEquals(reports, store.historyTail(id, 10));
    }

    @Test
    void automationRestChecksRosterAndSheetBytesInsideTheCommitLock() throws Exception {
        var store = new CampaignStore(dir);
        String id = store.create("Rest").getId();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        store.saveSheet(id, "source", "{\"marks\":2}");
        store.saveSheet(id, "recipient", "{\"marks\":1}");
        var originals = java.util.Map.of("source", store.loadSheet(id, "source"),
                "recipient", store.loadSheet(id, "recipient"));
        var changed = java.util.Map.of("source", mapper.createObjectNode().put("marks", 0),
                "recipient", mapper.createObjectNode().put("marks", 0));
        var report = mapper.createObjectNode().put("id", 1).put("ts", 10).put("text", "Recovery");
        Runnable staleRoster = () -> {
            assertTrue(Thread.holdsLock(store));
            throw new IllegalStateException("Roster changed.");
        };
        assertThrows(IllegalStateException.class,
                () -> store.saveAutomationResults(id, originals, changed, List.of(report), null, null, staleRoster));
        assertEquals(originals.get("source"), store.loadSheet(id, "source"));
        assertEquals(originals.get("recipient"), store.loadSheet(id, "recipient"));
        assertTrue(store.historyTail(id, 10).isEmpty());

        store.saveSheet(id, "recipient", "{\"marks\":3}");
        assertThrows(CampaignStore.StaleAutomationSheet.class, () -> store.saveAutomationResults(id, originals,
                changed, List.of(report), null, null, () -> assertTrue(Thread.holdsLock(store))));
        assertEquals(originals.get("source"), store.loadSheet(id, "source"));
        assertTrue(store.historyTail(id, 10).isEmpty());
    }

    @Test
    void automationClaimsCompareOfferAndSheetsBeforeWritingAnything() throws Exception {
        var store = new CampaignStore(dir);
        String id = store.create("Claims").getId();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        store.saveSheet(id, "source", "{\"notes\":\"source\"}");
        store.saveSheet(id, "recipient", "{\"notes\":\"old\"}");
        var originals = java.util.Map.of("source", store.loadSheet(id, "source"), "recipient", store.loadSheet(id, "recipient"));
        var changed = java.util.Map.of("recipient", mapper.createObjectNode().put("notes", "claimed"));
        var offer = mapper.createObjectNode().put("id", 1).put("ts", 10).put("text", "Offer");
        offer.putObject("claims").put("revision", 0).putArray("claimedBy");
        store.appendHistoryChecked(id, offer);
        var claimed = offer.deepCopy();
        claimed.withObject("/claims").put("revision", 1).withArray("/claimedBy").add("recipient");
        var report = mapper.createObjectNode().put("id", 2).put("ts", 11).put("text", "Claimed");
        int fear = store.load(id).getFear();

        var editedOffer = offer.deepCopy().put("text", "Edited offer");
        store.replaceHistoryEntry(id, 1, 10, editedOffer);
        assertThrows(CampaignStore.StaleAutomationClaim.class, () -> store.saveAutomationClaimResults(id, originals,
                changed, offer, claimed, List.of(report), fear + 1, null));
        assertEquals(originals.get("recipient"), store.loadSheet(id, "recipient"));
        assertEquals(List.of(editedOffer), store.historyTail(id, 10));
        assertEquals(fear, store.load(id).getFear());

        store.replaceHistoryEntry(id, 1, 10, offer);
        store.saveSheet(id, "source", "{\"notes\":\"changed\"}");
        assertThrows(CampaignStore.StaleAutomationSheet.class, () -> store.saveAutomationClaimResults(id, originals,
                changed, offer, claimed, List.of(report), fear + 1, null));
        assertEquals(originals.get("recipient"), store.loadSheet(id, "recipient"));
        assertEquals(List.of(offer), store.historyTail(id, 10));
        assertEquals(fear, store.load(id).getFear());

        var current = java.util.Map.of("source", store.loadSheet(id, "source"), "recipient", store.loadSheet(id, "recipient"));
        assertThrows(IllegalStateException.class, () -> store.saveAutomationClaimResults(id, current, changed,
                offer, claimed, List.of(report), fear + 1, null, () -> {
                    assertTrue(Thread.holdsLock(store));
                    throw new IllegalStateException("Roster changed.");
                }));
        assertEquals(current.get("recipient"), store.loadSheet(id, "recipient"));
        assertEquals(List.of(offer), store.historyTail(id, 10));
        assertEquals(fear, store.load(id).getFear());
        assertThrows(IllegalArgumentException.class, () -> store.saveAutomationClaimResults(id, current, changed,
                offer, claimed.deepCopy().put("id", 9), List.of(report), fear + 1, null));
        assertThrows(IllegalArgumentException.class, () -> store.saveAutomationClaimResults(id, current, changed,
                offer, claimed, List.of(report, report), fear + 1, null));
        assertThrows(IllegalArgumentException.class, () -> store.saveAutomationClaimResults(id, current, changed,
                offer, claimed, List.of(offer), fear + 1, null));
        var missing = offer.deepCopy().put("id", 8);
        assertThrows(CampaignStore.StaleAutomationClaim.class, () -> store.saveAutomationClaimResults(id, current, changed,
                missing, missing.deepCopy(), List.of(report), fear + 1, null));
        assertEquals(List.of(offer), store.historyTail(id, 10));

        store.saveAutomationClaimResults(id, current, changed, offer, claimed, List.of(report), fear + 1, null);
        assertEquals(List.of(claimed, report), store.historyTail(id, 10));
        assertEquals("claimed", mapper.readTree(store.loadSheet(id, "recipient")).path("notes").asText());
        assertEquals(fear + 1, store.load(id).getFear());
        assertThrows(CampaignStore.StaleAutomationClaim.class, () -> store.saveAutomationClaimResults(id, current,
                changed, offer, claimed, List.of(report), fear + 1, null));
        var latest = java.util.Map.of("source", store.loadSheet(id, "source"), "recipient", store.loadSheet(id, "recipient"));
        assertThrows(IllegalArgumentException.class, () -> store.saveAutomationClaimResults(id, latest, changed,
                claimed, claimed.deepCopy(), List.of(report), null, null));
        assertEquals(List.of(claimed, report), store.historyTail(id, 10));
    }

    @Test
    void automationClaimsRejectDamagedHistoryInsteadOfSavingPartialEffects() throws Exception {
        var store = new CampaignStore(dir);
        String id = store.create("Damaged claims").getId();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        store.saveSheet(id, "recipient", "{\"marks\":0}");
        String original = store.loadSheet(id, "recipient");
        var offer = mapper.createObjectNode().put("id", 1).put("ts", 10);
        store.appendHistoryChecked(id, offer);
        Files.writeString(dir.resolve(id).resolve("history.jsonl"), "{broken}\n", StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> store.saveAutomationClaimResults(id, java.util.Map.of("recipient", original),
                java.util.Map.of("recipient", mapper.createObjectNode().put("marks", 1)),
                offer, offer.deepCopy().put("claimed", true), List.of(), null, null));
        assertEquals(original, store.loadSheet(id, "recipient"));
        assertTrue(Files.notExists(dir.resolve(id).resolve("sheet-transfer.json")));
    }

    @Test
    void automationClaimLookupRequiresReadableHistoryAndUsesExactIdentity() throws Exception {
        var store = new CampaignStore(dir);
        String id = store.create("Claim lookup").getId();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var offer = mapper.createObjectNode().put("id", 1).put("ts", 10).put("text", "Offer");
        store.appendHistoryChecked(id, offer);
        for (int i = 2; i < 160; i++)
            store.appendHistoryChecked(id, mapper.createObjectNode().put("id", i).put("ts", 10 + i));
        assertEquals(offer, store.automationClaimEntry(id, 1, 10));
        assertNull(store.automationClaimEntry(id, 1, 11));
        assertNull(store.automationClaimEntry(id, 999, 10));
        assertThrows(IllegalArgumentException.class, () -> store.automationClaimEntry(id, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> store.automationClaimEntry(id, 1, -1));
        ((com.fasterxml.jackson.databind.node.ObjectNode) store.automationClaimEntry(id, 1, 10)).put("text", "Changed");
        assertEquals(offer, store.automationClaimEntry(id, 1, 10));

        Path journal = dir.resolve(id).resolve("sheet-transfer.json");
        Files.writeString(journal, "{broken}");
        assertThrows(IOException.class, () -> store.automationClaimEntry(id, 1, 10));
        Files.delete(journal);
        assertEquals(offer, store.automationClaimEntry(id, 1, 10));

        Files.writeString(dir.resolve(id).resolve("history.jsonl"), "{broken}\n", StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> store.automationClaimEntry(id, 1, 10));
        assertThrows(IOException.class, () -> store.automationClaimEntry(id, 999, 10));
    }

    @Test
    void automationClaimsRejectDuplicateOfferIdentitiesAcrossIndexUpdatesAndReloads() throws Exception {
        var store = new CampaignStore(dir);
        String id = store.create("Ambiguous claims").getId();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var offer = mapper.createObjectNode().put("id", 1).put("ts", 10).put("text", "Offer");
        var other = mapper.createObjectNode().put("id", 2).put("ts", 20).put("text", "Other");
        store.saveSheet(id, "recipient", "{\"marks\":0}");
        String original = store.loadSheet(id, "recipient");
        store.appendHistoryChecked(id, offer);
        store.appendHistoryChecked(id, other);
        assertEquals(offer, store.automationClaimEntry(id, 1, 10));

        store.appendHistoryChecked(id, offer.deepCopy().put("text", "Duplicate"));
        assertEquals(offer, store.historyEntry(id, 1, 10), "legacy reads still use the first match");
        assertThrows(IOException.class, () -> store.automationClaimEntry(id, 1, 10));
        assertThrows(IOException.class, () -> new CampaignStore(dir).automationClaimEntry(id, 1, 10));
        assertThrows(IOException.class, () -> store.saveAutomationClaimResults(id, java.util.Map.of("recipient", original),
                java.util.Map.of("recipient", mapper.createObjectNode().put("marks", 1)), offer,
                offer.deepCopy().put("claimed", true), List.of(), null, null));
        assertEquals(original, store.loadSheet(id, "recipient"));
        assertEquals(3, store.historyTail(id, 10).size());

        store.deleteHistoryEntry(id, 1, 10);
        assertNull(store.automationClaimEntry(id, 1, 10));
        store.appendHistoryChecked(id, offer);
        assertEquals(offer, store.automationClaimEntry(id, 1, 10));
        store.replaceHistoryEntry(id, 2, 20, offer.deepCopy().put("text", "Replacement"));
        assertThrows(IOException.class, () -> store.automationClaimEntry(id, 1, 10));
        assertThrows(IOException.class, () -> new CampaignStore(dir).automationClaimEntry(id, 1, 10));
        store.deleteHistoryEntry(id, 1, 10);
        store.appendHistoryChecked(id, offer);
        assertEquals(offer, store.automationClaimEntry(id, 1, 10));
    }

    @Test
    void claimJournalReplayRejectsConflictingHistoryBeforeWritingSheets() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String conflict : List.of("missing", "changed", "duplicate-offer", "changed-report", "duplicate-report", "duplicate-journal-report")) {
            var store = new CampaignStore(dir);
            String id = store.create(conflict).getId();
            var offer = mapper.createObjectNode().put("id", 1).put("ts", 10).put("text", "Offer");
            var replacement = offer.deepCopy().put("claimed", true);
            var report = mapper.createObjectNode().put("id", 2).put("ts", 20).put("text", "Claimed");
            if (!"missing".equals(conflict))
                store.appendHistoryChecked(id, "changed".equals(conflict) ? offer.deepCopy().put("text", "Edited") : offer);
            if ("duplicate-offer".equals(conflict)) store.appendHistoryChecked(id, offer);
            if ("changed-report".equals(conflict)) store.appendHistoryChecked(id, report.deepCopy().put("text", "Other"));
            if ("duplicate-report".equals(conflict)) {
                store.appendHistoryChecked(id, report);
                store.appendHistoryChecked(id, report);
            }
            store.saveSheet(id, "recipient", "{\"marks\":0}");
            Path folder = dir.resolve(id), sheet = folder.resolve("sheets").resolve("recipient.json");
            String before = Files.readString(sheet);
            var journal = mapper.createObjectNode();
            journal.putObject("recipient").put("marks", 1);
            journal.set("$claimExpected", offer);
            journal.set("$roll", replacement);
            journal.putArray("$messages").add(report);
            if ("duplicate-journal-report".equals(conflict)) journal.withArray("$messages").add(report);
            Files.writeString(folder.resolve("sheet-transfer.json"), journal.toString());
            assertThrows(IOException.class, () -> new CampaignStore(dir).loadSheet(id, "recipient"), conflict);
            assertEquals(before, Files.readString(sheet), conflict);
            assertTrue(Files.exists(folder.resolve("sheet-transfer.json")), conflict);
        }
    }

    @Test
    void claimJournalReplayAcceptsBothOriginalAndAlreadyWrittenOfferAndReports() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (boolean alreadyWritten : List.of(false, true)) {
            var store = new CampaignStore(dir);
            String id = store.create("Replay").getId();
            var offer = mapper.createObjectNode().put("id", 1).put("ts", 10).put("text", "Offer");
            var replacement = offer.deepCopy().put("claimed", true);
            var report = mapper.createObjectNode().put("id", 2).put("ts", 20).put("text", "Claimed");
            store.appendHistoryChecked(id, alreadyWritten ? replacement : offer);
            if (alreadyWritten) store.appendHistoryChecked(id, report);
            store.saveSheet(id, "recipient", "{\"marks\":0}");
            var journal = mapper.createObjectNode();
            journal.putObject("recipient").put("marks", 1);
            journal.set("$claimExpected", offer);
            journal.set("$roll", replacement);
            journal.set("$message", report);
            Path journalPath = dir.resolve(id).resolve("sheet-transfer.json");
            Files.writeString(journalPath, journal.toString());
            var reopened = new CampaignStore(dir);
            assertEquals(1, mapper.readTree(reopened.loadSheet(id, "recipient")).path("marks").intValue());
            assertEquals(List.of(replacement, report), reopened.historyTail(id, 10));
            assertTrue(Files.notExists(journalPath));
            assertEquals(List.of(replacement, report), new CampaignStore(dir).historyTail(id, 10));
        }
    }

    @Test
    void malformedClaimJournalDoesNotWriteSheets() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String defect : List.of("expected", "missing-replacement", "different-replacement", "reports", "report")) {
            var store = new CampaignStore(dir);
            String id = store.create(defect).getId();
            var offer = mapper.createObjectNode().put("id", 1).put("ts", 10);
            store.appendHistoryChecked(id, offer);
            store.saveSheet(id, "recipient", "{\"marks\":0}");
            Path folder = dir.resolve(id), sheet = folder.resolve("sheets").resolve("recipient.json");
            String before = Files.readString(sheet);
            var journal = mapper.createObjectNode();
            journal.putObject("recipient").put("marks", 1);
            journal.set("$claimExpected", offer);
            journal.set("$roll", offer.deepCopy().put("claimed", true));
            if ("expected".equals(defect)) journal.putNull("$claimExpected");
            if ("missing-replacement".equals(defect)) journal.remove("$roll");
            if ("different-replacement".equals(defect)) journal.withObject("$roll").put("id", 9);
            if ("reports".equals(defect)) journal.put("$messages", "invalid");
            if ("report".equals(defect)) journal.putArray("$messages").addObject().put("id", 2);
            Files.writeString(folder.resolve("sheet-transfer.json"), journal.toString());
            assertThrows(IOException.class, () -> new CampaignStore(dir).loadSheet(id, "recipient"), defect);
            assertEquals(before, Files.readString(sheet), defect);
        }
    }

    @Test
    void claimJournalReplayRecoversTornReportAppend() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var store = new CampaignStore(dir);
        String id = store.create("Torn claim").getId();
        var offer = mapper.createObjectNode().put("id", 1).put("ts", 10);
        var replacement = offer.deepCopy().put("claimed", true);
        var report = mapper.createObjectNode().put("id", 2).put("ts", 20).put("text", "Claimed");
        store.appendHistoryChecked(id, replacement);
        Path folder = dir.resolve(id), history = folder.resolve("history.jsonl");
        long offset = Files.size(history);
        String payload = report + "\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("history-append.json"),
                mapper.createObjectNode().put("offset", offset).put("append", payload).toString());
        Files.write(history, Arrays.copyOf(bytes, bytes.length - 4), StandardOpenOption.APPEND);
        var journal = mapper.createObjectNode();
        journal.putObject("recipient").put("marks", 1);
        journal.set("$claimExpected", offer);
        journal.set("$roll", replacement);
        journal.set("$message", report);
        Files.writeString(folder.resolve("sheet-transfer.json"), journal.toString());
        var reopened = new CampaignStore(dir);
        assertEquals(1, mapper.readTree(reopened.loadSheet(id, "recipient")).path("marks").intValue());
        assertEquals(List.of(replacement, report), reopened.historyTail(id, 10));
        assertTrue(Files.notExists(folder.resolve("history-append.json")));
        assertTrue(Files.notExists(folder.resolve("sheet-transfer.json")));
    }

    @Test
    void competingClaimsCannotBothCommitAndInterruptedClaimReplayIsIdempotent() throws Exception {
        var store = new CampaignStore(dir);
        String id = store.create("Concurrent claims").getId();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var offer = mapper.createObjectNode().put("id", 1).put("ts", 10);
        offer.putObject("claims").put("revision", 0).putArray("claimedBy");
        store.appendHistoryChecked(id, offer);
        for (String actor : List.of("one", "two")) store.saveSheet(id, actor, "{\"marks\":0}");
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (String actor : List.of("one", "two")) {
                var original = java.util.Map.of(actor, store.loadSheet(id, actor));
                var replacement = offer.deepCopy();
                replacement.withObject("/claims").put("revision", 1).withArray("/claimedBy").add(actor);
                var changed = java.util.Map.of(actor, mapper.createObjectNode().put("marks", 1));
                var report = mapper.createObjectNode().put("id", actor.equals("one") ? 2 : 3).put("ts", 11).put("text", actor);
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        store.saveAutomationClaimResults(id, original, changed, offer, replacement, List.of(report), null, null);
                        return actor;
                    } catch (CampaignStore.StaleAutomationClaim stale) {
                        return "stale";
                    }
                }));
            }
            start.countDown();
            var outcomes = List.of(results.get(0).get(), results.get(1).get());
            assertEquals(1, outcomes.stream().filter("stale"::equals).count());
            String winner = outcomes.stream().filter(value -> !"stale".equals(value)).findFirst().orElseThrow();
            String loser = winner.equals("one") ? "two" : "one";
            var messages = store.historyTail(id, 10);
            assertEquals(2, messages.size());
            assertEquals(winner, messages.getFirst().at("/claims/claimedBy/0").asText());
            assertEquals(1, mapper.readTree(store.loadSheet(id, winner)).path("marks").intValue());
            assertEquals(0, mapper.readTree(store.loadSheet(id, loser)).path("marks").intValue());

            var transaction = mapper.createObjectNode();
            transaction.set(winner, mapper.readTree(store.loadSheet(id, winner)));
            transaction.set("$roll", messages.getFirst());
            transaction.set("$message", messages.get(1));
            Path journal = dir.resolve(id).resolve("sheet-transfer.json");
            Files.writeString(journal, transaction.toString());
            var reopened = new CampaignStore(dir);
            assertEquals(messages, reopened.historyTail(id, 10));
            assertEquals(1, mapper.readTree(reopened.loadSheet(id, winner)).path("marks").intValue());
            assertTrue(Files.notExists(journal));
            assertEquals(messages, new CampaignStore(dir).historyTail(id, 10));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fullStressProvenanceSurvivesReloadAndAllSheetPersistencePaths() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Stress");
        store.saveSheet(campaign.getId(), "hero", """
                {"stress":{"slots":6,"on":6},"effects":[]}
                """);
        var reopened = new CampaignStore(dir);
        var sheet = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(reopened.loadSheet(campaign.getId(), "hero"));
        assertTrue(sheet.path("effects").get(0).path("appliedByFullStress").asBoolean());
        sheet.withObject("/stress").put("on", 5);
        reopened.saveSheets(campaign.getId(), java.util.Map.of("hero", sheet));
        assertTrue(sheet.path("effects").isEmpty());
        assertTrue(mapper.readTree(reopened.loadSheet(campaign.getId(), "hero")).path("effects").isEmpty());
        sheet.withObject("/stress").put("on", 6);
        var actor = Campaign.Actor.builder().id("hero").name("Hero").build();
        campaign.getActors().add(actor);
        reopened.save(campaign);
        reopened.renameActor(campaign, actor, "Renamed", sheet);
        assertTrue(sheet.path("effects").get(0).path("appliedByFullStress").asBoolean());
        sheet.withObject("/stress").put("on", 0);
        reopened.saveSheetsAndRoll(campaign.getId(), java.util.Map.of("hero", sheet), null);
        assertTrue(sheet.path("effects").isEmpty());
        sheet.putArray("effects").addObject().put("id", "original").put("name", "Vulnerable").put("kind", "debuff");
        sheet.withObject("/stress").put("on", 6);
        reopened.saveSheet(campaign.getId(), "hero", sheet.toString());
        var saved = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(reopened.loadSheet(campaign.getId(), "hero"));
        saved.withObject("/stress").put("on", 0);
        reopened.saveSheet(campaign.getId(), "hero", saved.toString());
        assertEquals("original", mapper.readTree(new CampaignStore(dir).loadSheet(campaign.getId(), "hero"))
                .path("effects").get(0).path("id").asText());
    }

    @Test
    void assignsEightDistinctPlayerColorsAndPreservesThemThroughReloadAndAccountChanges() throws Exception {
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Colors");
        for (int i = 0; i < 8; i++) {
            campaign.getPlayers().add(Campaign.Player.builder().id("player" + i).name("Player " + i).build());
            store.save(campaign);
        }
        List<String> colors = campaign.getPlayers().stream().map(Campaign.Player::getColor).toList();
        assertEquals(Campaign.PLAYER_COLORS, colors);
        assertEquals(8, colors.stream().distinct().count());
        assertTrue(colors.stream().noneMatch(Campaign.DEFAULT_DM_COLOR::equals));
        assertEquals("#e53935", campaign.getDmColor());
        Campaign reopened = new CampaignStore(dir).load(campaign.getId());
        assertEquals(colors, reopened.getPlayers().stream().map(Campaign.Player::getColor).toList());
        reopened.getPlayers().add(Campaign.Player.builder().id("extra").name("Extra").build());
        store.save(reopened);
        assertEquals(colors.getFirst(), reopened.player("extra").getColor());
        reopened.getPlayers().removeIf(p -> p.getId().equals("player3"));
        reopened.player("player1").setName("Renamed");
        reopened.getPlayers().add(Campaign.Player.builder().id("replacement").name("Replacement").build());
        store.save(reopened);
        assertEquals(colors.get(3), reopened.player("replacement").getColor());
        assertEquals(colors.get(1), store.load(campaign.getId()).player("player1").getColor());
    }

    @Test
    void legacyColorsAreBackfilledOnDiskWithoutReplacingSavedPlayerOrDmColors() throws Exception {
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Legacy");
        Path file = dir.resolve(campaign.getId()).resolve("campaign.json");
        Files.writeString(file, """
                {"id":"%s","name":"Legacy","dmColor":"#123456","players":[
                  {"id":"new","name":"Missing"},{"id":"saved","name":"Saved","color":"#2979FF"},
                  {"id":"blank","name":"Blank","color":""}]}
                """.formatted(campaign.getId()));
        Campaign loaded = store.load(campaign.getId());
        assertEquals("#123456", loaded.getDmColor());
        assertEquals("#2979FF", loaded.player("saved").getColor());
        assertEquals("#00c853", loaded.player("new").getColor());
        assertEquals("#ffd600", loaded.player("blank").getColor());
        var disk = new com.fasterxml.jackson.databind.ObjectMapper().readTree(file.toFile());
        assertEquals("#00c853", disk.path("players").get(0).path("color").asText());
        assertEquals(loaded, new CampaignStore(dir).load(campaign.getId()));
        Files.writeString(file, "{\"id\":\"" + campaign.getId() + "\",\"name\":\"Empty legacy\"}");
        assertEquals(Campaign.DEFAULT_DM_COLOR, store.load(campaign.getId()).getDmColor());
        assertEquals(Campaign.DEFAULT_DM_COLOR,
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(file.toFile()).path("dmColor").asText());
    }

    @Test
    void interruptedActorRenameRecoversCampaignAndSheetTogether() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Names");
        Campaign.Actor actor = Campaign.Actor.builder().id("hero").name("Before").build();
        campaign.getActors().add(actor);
        campaign.getActors().add(Campaign.Actor.builder().id("new").name("New").build());
        store.save(campaign);
        var transaction = mapper.createObjectNode();
        transaction.putObject("$actorName").put("id", "hero").put("name", "After");
        transaction.putObject("hero").put("name", "After").put("notes", "Keep");
        Path journal = dir.resolve(campaign.getId()).resolve("sheet-transfer.json");
        Files.writeString(journal, transaction.toString());
        CampaignStore reopened = new CampaignStore(dir);
        assertEquals("After", reopened.load(campaign.getId()).actor("hero").getName());
        assertEquals("After", mapper.readTree(reopened.loadSheet(campaign.getId(), "hero")).path("name").asText());
        assertEquals("Keep", mapper.readTree(reopened.loadSheet(campaign.getId(), "hero")).path("notes").asText());
        assertTrue(Files.notExists(journal));
        Campaign recovered = reopened.load(campaign.getId());
        reopened.renameActor(recovered, recovered.actor("new"), "Without sheet", null);
        assertEquals("Without sheet", reopened.load(campaign.getId()).actor("new").getName());
        assertNull(reopened.loadSheet(campaign.getId(), "new"));
    }

    @Test
    void fearRewardRecoveryJournalsCampaignFearAndClaimTogether() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Fear reward");
        campaign.setFear(5);
        store.save(campaign);
        var roll = mapper.createObjectNode().put("id", 1).put("ts", 10).put("rewardClaimed", false);
        store.appendHistoryChecked(campaign.getId(), roll);
        roll.put("rewardClaimed", true);
        var transaction = mapper.createObjectNode().put("$fear", 4);
        transaction.set("$roll", roll);
        var report = mapper.createObjectNode().put("id", 2).put("ts", 11)
                .put("text", "Removed 1 GM Fear instead of gaining Hope.");
        transaction.set("$message", report);
        transaction.set("hero", mapper.createObjectNode().put("featureRevision", 1));
        Path journal = dir.resolve(campaign.getId()).resolve("sheet-transfer.json");
        Files.writeString(journal, transaction.toString());
        CampaignStore reopened = new CampaignStore(dir);
        assertEquals(4, reopened.load(campaign.getId()).getFear());
        assertEquals(List.of(roll, report), reopened.historyTail(campaign.getId(), 20));
        assertTrue(reopened.historyTail(campaign.getId(), 20).getFirst().path("rewardClaimed").asBoolean());
        assertEquals(1, mapper.readTree(reopened.loadSheet(campaign.getId(), "hero")).path("featureRevision").asInt());
        assertTrue(Files.notExists(journal));
        assertEquals(4, reopened.load(campaign.getId()).getFear());
    }

    @Test
    void sessionResourcesArePersistedLikeOtherResources() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Session resources");
        store.saveSheet(campaign.getId(), "hero", "{\"resources\":{\"tide\":3,\"favor\":4}}");
        var disk = mapper.readTree(new CampaignStore(dir).loadSheet(campaign.getId(), "hero"));
        assertEquals(3, disk.path("resources").path("tide").asInt());
        assertEquals(4, disk.path("resources").path("favor").asInt());
        var hero = (com.fasterxml.jackson.databind.node.ObjectNode) disk;
        ((com.fasterxml.jackson.databind.node.ObjectNode) hero.path("resources")).put("tide", 2);
        store.saveSheets(campaign.getId(), java.util.Map.of("hero", hero));
        assertEquals(2, mapper.readTree(new CampaignStore(dir).loadSheet(campaign.getId(), "hero")).path("resources").path("tide").asInt());
    }

    @Test
    void newDeathMoveMessageAndScarsAreJournaledWithoutExistingHistoryAndReplayIsIdempotent() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Death moves");
        var sheet = mapper.createObjectNode().put("scars", 1);
        var message = mapper.createObjectNode().put("id", 1).put("ts", 10).put("deathMove", "avoid");
        store.saveSheetsAndMessage(campaign.getId(), java.util.Map.of("hero", sheet), message);
        assertEquals(List.of(message), store.historyTail(campaign.getId(), 20));
        assertEquals(1, mapper.readTree(store.loadSheet(campaign.getId(), "hero")).path("scars").asInt());
        var transaction = mapper.createObjectNode();
        transaction.set("hero", sheet);
        transaction.set("$message", message);
        Path journal = dir.resolve(campaign.getId()).resolve("sheet-transfer.json");
        Files.writeString(journal, transaction.toString());
        CampaignStore reopened = new CampaignStore(dir);
        assertEquals(List.of(message), reopened.historyTail(campaign.getId(), 20));
        assertTrue(Files.notExists(journal));
        var second = mapper.createObjectNode().put("id", 2).put("ts", 11).put("deathMove", "risk");
        reopened.saveSheetsAndMessage(campaign.getId(), java.util.Map.of("hero", sheet), second);
        assertEquals(List.of(message, second), reopened.historyTail(campaign.getId(), 20));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> reopened.replaceHistoryEntry(campaign.getId(), 3, 12, second));
    }

    @Test
    void multipleDeathReportsAndRecoveryClaimReplayWithoutDuplicates() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Atomic death results");
        var original = mapper.createObjectNode().put("id", 1).put("ts", 10).put("type", "roll");
        original.putObject("deathResult").put("claimed", false);
        store.appendHistoryChecked(campaign.getId(), original);
        var claimed = original.deepCopy();
        claimed.withObject("/deathResult").put("claimed", true);
        var expiration = mapper.createObjectNode().put("id", 2).put("ts", 11).put("text", "Spectral Form ended");
        var ward = mapper.createObjectNode().put("id", 3).put("ts", 12).put("text", "Life Ward consumed");
        var sheet = mapper.createObjectNode().put("permanentHp", 1);
        store.saveSheetsAndResults(campaign.getId(), java.util.Map.of("hero", sheet), claimed, List.of(expiration, ward));
        assertEquals(List.of(claimed, expiration, ward), store.historyTail(campaign.getId(), 20));
        var transaction = mapper.createObjectNode();
        transaction.set("hero", sheet);
        transaction.set("$roll", claimed);
        transaction.putArray("$messages").add(expiration).add(ward);
        Path journal = dir.resolve(campaign.getId()).resolve("sheet-transfer.json");
        Files.writeString(journal, transaction.toString());
        CampaignStore reopened = new CampaignStore(dir);
        assertEquals(List.of(claimed, expiration, ward), reopened.historyTail(campaign.getId(), 20));
        assertEquals(1, mapper.readTree(reopened.loadSheet(campaign.getId(), "hero")).path("permanentHp").asInt());
        assertTrue(Files.notExists(journal));
    }

    @Test
    void interruptedRewardReplaysSheetAndHistoryTogetherBeforeHistoryReads() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Rewards");
        var roll = mapper.createObjectNode().put("id", 1).put("ts", 10).put("rewardClaimed", false);
        store.appendHistoryChecked(campaign.getId(), roll);
        store.saveSheet(campaign.getId(), "hero", "{\"hope\":{\"on\":0}}");
        roll.put("rewardClaimed", true);
        var transaction = mapper.createObjectNode();
        transaction.set("hero", mapper.readTree("{\"hope\":{\"on\":1}}"));
        transaction.set("$roll", roll);
        Path journal = dir.resolve(campaign.getId()).resolve("sheet-transfer.json");
        Files.writeString(journal, transaction.toString());
        CampaignStore reopened = new CampaignStore(dir);
        assertTrue(reopened.historyTail(campaign.getId(), 20).getFirst().path("rewardClaimed").asBoolean());
        assertEquals(1, mapper.readTree(reopened.loadSheet(campaign.getId(), "hero")).path("hope").path("on").asInt());
        assertTrue(Files.notExists(journal));
        assertEquals(1, mapper.readTree(new CampaignStore(dir).loadSheet(campaign.getId(), "hero")).path("hope").path("on").asInt());
    }

    @Test
    void deletingHistoryPersistsAndDoesNotDeleteReusedLegacyIds() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Chat");
        var first = mapper.createObjectNode().put("id", 1).put("ts", 10).put("text", "older session");
        var second = mapper.createObjectNode().put("id", 1).put("ts", 20).put("text", "newer session");
        store.appendHistoryChecked(campaign.getId(), first);
        store.appendHistoryChecked(campaign.getId(), second);
        store.deleteHistoryEntry(campaign.getId(), 1, 20);
        assertEquals(List.of(first), new CampaignStore(dir).historyTail(campaign.getId(), 100));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> store.deleteHistoryEntry(campaign.getId(), 1, 20));
        assertEquals(List.of(first), store.historyTail(campaign.getId(), 100));
    }

    @Test
    void damagedHistoryReportsDeletionFailureWithoutChangingTheFile() throws Exception {
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Chat");
        Path history = dir.resolve(campaign.getId()).resolve("history.jsonl");
        String original = "{\"id\":1,\"ts\":10}\nnot json\n";
        Files.writeString(history, original);
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> store.deleteHistoryEntry(campaign.getId(), 1, 10));
        assertEquals(original, Files.readString(history));
    }

    @Test
    void journaledAppendToLongHistoryPreservesExistingFileAndBytes() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Long history");
        Path history = dir.resolve(campaign.getId()).resolve("history.jsonl");
        StringBuilder legacy = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            legacy.append("{\"id\":").append(i).append(",\"ts\":").append(i + 100)
                    .append(",\"text\":\"legacy ").append(i).append(" — 雪\"}\r\n");
        }
        Files.writeString(history, legacy, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(history);
        Object key = Files.readAttributes(history, BasicFileAttributes.class).fileKey();
        assertEquals(4999, store.historyTail(campaign.getId(), 1).getFirst().path("id").asLong());
        assertEquals("legacy 12 — 雪", store.historyEntry(campaign.getId(), 12, 112).path("text").asText());
        var message = mapper.createObjectNode().put("id", 5000).put("ts", 5100).put("text", "new report");
        store.saveSheetsAndMessage(campaign.getId(), java.util.Map.of(), message);
        assertEquals(key, Files.readAttributes(history, BasicFileAttributes.class).fileKey());
        assertArrayEquals(before, Arrays.copyOf(Files.readAllBytes(history), before.length));
        assertEquals(List.of(message), store.historyTail(campaign.getId(), 1));
        assertEquals(5001, store.historyTail(campaign.getId(), 6000).size());
        assertEquals(message, new CampaignStore(dir).historyEntry(campaign.getId(), 5000, 5100));
    }

    @Test
    void indexedReplacementsKeepOriginalPositionIdentityWhispersAndDefensiveCopies() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Indexed history");
        var first = mapper.createObjectNode().put("id", 1).put("ts", 10).put("text", "first");
        var whisper = mapper.createObjectNode().put("id", 2).put("ts", 20).put("text", "secret")
                .put("whisper", true).put("to", "player");
        var reused = mapper.createObjectNode().put("id", 1).put("ts", 30).put("text", "next session");
        for (var entry : List.of(first, whisper, reused)) store.appendHistoryChecked(campaign.getId(), entry);
        assertEquals(first, store.historyEntry(campaign.getId(), 1, 10));
        assertEquals(reused, store.historyEntry(campaign.getId(), 1, 30));
        assertNull(store.historyEntry(campaign.getId(), 1, 11));
        ((com.fasterxml.jackson.databind.node.ObjectNode) store.historyEntry(campaign.getId(), 2, 20))
                .put("text", "uncommitted");
        ((com.fasterxml.jackson.databind.node.ObjectNode) store.historyTail(campaign.getId(), 3).getFirst())
                .put("text", "uncommitted");
        assertEquals(first, store.historyEntry(campaign.getId(), 1, 10));
        assertEquals(whisper, store.historyEntry(campaign.getId(), 2, 20));
        var replacement = whisper.deepCopy().put("text", "a longer edited secret — 雪");
        store.replaceHistoryEntry(campaign.getId(), 2, 20, replacement);
        assertEquals(List.of(first, replacement, reused), store.historyTail(campaign.getId(), 10));
        assertEquals(replacement, store.historyEntry(campaign.getId(), 2, 20));
        store.deleteHistoryEntry(campaign.getId(), 1, 10);
        assertNull(store.historyEntry(campaign.getId(), 1, 10));
        assertEquals(List.of(replacement, reused), store.historyTail(campaign.getId(), 10));
        assertEquals(List.of(replacement, reused), new CampaignStore(dir).historyTail(campaign.getId(), 10));
    }

    @Test
    void historyIndexDetectsExternalAppendRewriteReplacementDeletionAndRecreation() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("External history");
        Path history = dir.resolve(campaign.getId()).resolve("history.jsonl");
        var first = mapper.createObjectNode().put("id", 1).put("ts", 10).put("text", "A");
        store.appendHistoryChecked(campaign.getId(), first);
        assertEquals(first, store.historyEntry(campaign.getId(), 1, 10));
        var edited = first.deepCopy().put("text", "B");
        FileTime oldTime = Files.getLastModifiedTime(history);
        Files.writeString(history, edited + "\n");
        Files.setLastModifiedTime(history, FileTime.fromMillis(oldTime.toMillis() + 2000));
        assertEquals(edited, store.historyEntry(campaign.getId(), 1, 10));
        var second = mapper.createObjectNode().put("id", 2).put("ts", 20).put("text", "C");
        Files.writeString(history, second + "\n", StandardOpenOption.APPEND);
        assertEquals(List.of(edited, second), store.historyTail(campaign.getId(), 10));
        Path external = history.resolveSibling("external-history.jsonl");
        Files.writeString(external, second + "\n");
        Files.move(external, history, StandardCopyOption.REPLACE_EXISTING);
        assertNull(store.historyEntry(campaign.getId(), 1, 10));
        assertEquals(second, store.historyEntry(campaign.getId(), 2, 20));
        Files.writeString(history, "");
        assertTrue(store.historyTail(campaign.getId(), 10).isEmpty());
        Files.delete(history);
        assertNull(store.historyEntry(campaign.getId(), 2, 20));
        Files.writeString(history, first + "\n");
        assertEquals(List.of(first), store.historyTail(campaign.getId(), 10));
    }

    @Test
    void damagedHistoryRemainsBestEffortToReadButStrictMutationsAndRecoveryFail() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Damaged history");
        Path folder = dir.resolve(campaign.getId());
        Path history = folder.resolve("history.jsonl");
        var entry = mapper.createObjectNode().put("id", 1).put("ts", 10).put("text", "valid");
        store.appendHistoryChecked(campaign.getId(), entry);
        assertEquals(entry, store.historyEntry(campaign.getId(), 1, 10));
        Files.writeString(history, "not json\n", StandardOpenOption.APPEND);
        String damaged = Files.readString(history);
        assertEquals(List.of(entry), store.historyTail(campaign.getId(), 10));
        assertTrue(store.historyTail(campaign.getId(), 1).isEmpty());
        assertEquals(entry, store.historyEntry(campaign.getId(), 1, 10));
        assertThrows(IOException.class, () -> store.replaceHistoryEntry(campaign.getId(), 1, 10,
                entry.deepCopy().put("text", "edited")));
        assertThrows(IOException.class, () -> store.deleteHistoryEntry(campaign.getId(), 1, 10));
        assertEquals(damaged, Files.readString(history));
        var newEntry = mapper.createObjectNode().put("id", 2).put("ts", 20);
        assertThrows(IOException.class, () -> store.saveSheetsAndMessage(campaign.getId(), java.util.Map.of(), newEntry));
        assertEquals(damaged, Files.readString(history));
        assertTrue(Files.exists(folder.resolve("sheet-transfer.json")));
        assertNull(store.historyEntry(campaign.getId(), 1, 10));
        assertTrue(store.historyTail(campaign.getId(), 10).isEmpty());
        assertThrows(IOException.class, () -> store.loadSheet(campaign.getId(), "hero"));
        Files.delete(folder.resolve("sheet-transfer.json"));
        store.appendHistoryChecked(campaign.getId(), newEntry);
        assertEquals(List.of(entry, newEntry), store.historyTail(campaign.getId(), 10));
    }

    @Test
    void invalidUtf8HistoryPreservesWholeReadFailureAndStrictWriteFailure() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Invalid UTF-8 history");
        var entry = mapper.createObjectNode().put("id", 1).put("ts", 10);
        store.appendHistoryChecked(campaign.getId(), entry);
        Path history = dir.resolve(campaign.getId()).resolve("history.jsonl");
        Files.write(history, new byte[]{(byte) 0xff, '\n'}, StandardOpenOption.APPEND);
        byte[] damaged = Files.readAllBytes(history);
        assertTrue(store.historyTail(campaign.getId(), 10).isEmpty());
        assertNull(store.historyEntry(campaign.getId(), 1, 10));
        assertThrows(IOException.class, () -> store.deleteHistoryEntry(campaign.getId(), 1, 10));
        assertThrows(IOException.class, () -> store.replaceHistoryEntry(campaign.getId(), 1, 10, entry));
        assertArrayEquals(damaged, Files.readAllBytes(history));
    }

    @Test
    void historyReadsRecoverFirstMessageEvenWhenHistoryDoesNotYetExist() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("First journaled message");
        var message = mapper.createObjectNode().put("id", 1).put("ts", 10).put("text", "Recovered");
        var transaction = mapper.createObjectNode();
        transaction.putObject("hero").put("revision", 1);
        transaction.set("$message", message);
        Path journal = dir.resolve(campaign.getId()).resolve("sheet-transfer.json");
        Files.writeString(journal, transaction.toString());
        assertEquals(List.of(message), store.historyTail(campaign.getId(), 1));
        assertEquals(message, store.historyEntry(campaign.getId(), 1, 10));
        assertEquals(1, mapper.readTree(store.loadSheet(campaign.getId(), "hero")).path("revision").asInt());
        assertTrue(Files.notExists(journal));
    }

    @Test
    void tornJournaledAppendCompletesBeforeReplayWithoutDuplicatingOrLosingMessages() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Torn append");
        var first = mapper.createObjectNode().put("id", 1).put("ts", 10);
        store.appendHistoryChecked(campaign.getId(), first);
        Path folder = dir.resolve(campaign.getId());
        Path history = folder.resolve("history.jsonl");
        long offset = Files.size(history);
        var message = mapper.createObjectNode().put("id", 2).put("ts", 20).put("text", "whisper 雪");
        var transaction = mapper.createObjectNode();
        transaction.putObject("hero").put("revision", 2);
        transaction.set("$message", message);
        String payload = message + "\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Path appendJournal = folder.resolve("history-append.json");
        Files.writeString(appendJournal, mapper.createObjectNode().put("offset", offset).put("append", payload).toString());
        Files.write(history, Arrays.copyOf(bytes, bytes.length - 4), StandardOpenOption.APPEND);
        Path sheetJournal = folder.resolve("sheet-transfer.json");
        Files.writeString(sheetJournal, transaction.toString());
        CampaignStore reopened = new CampaignStore(dir);
        assertEquals(message, reopened.historyEntry(campaign.getId(), 2, 20));
        assertEquals(List.of(first, message), reopened.historyTail(campaign.getId(), 10));
        assertEquals(2, mapper.readTree(reopened.loadSheet(campaign.getId(), "hero")).path("revision").asInt());
        assertTrue(Files.notExists(appendJournal));
        assertTrue(Files.notExists(sheetJournal));
        Files.writeString(appendJournal, mapper.createObjectNode().put("offset", offset).put("append", payload).toString());
        Files.writeString(sheetJournal, transaction.toString());
        assertEquals(List.of(first, message), new CampaignStore(dir).historyTail(campaign.getId(), 10));
        assertTrue(Files.notExists(appendJournal));
        assertTrue(Files.notExists(sheetJournal));
    }

    @Test
    void pendingAppendRejectsExternalChangesWithoutOverwritingThemOrReturningCachedData() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Conflicting append");
        store.saveSheet(campaign.getId(), "hero", "{\"revision\":1}");
        assertNotNull(store.loadSheet(campaign.getId(), "hero"));
        var first = mapper.createObjectNode().put("id", 1).put("ts", 10);
        store.appendHistoryChecked(campaign.getId(), first);
        assertNotNull(store.historyEntry(campaign.getId(), 1, 10));
        Path folder = dir.resolve(campaign.getId());
        Path history = folder.resolve("history.jsonl");
        long offset = Files.size(history);
        Files.writeString(folder.resolve("history-append.json"),
                mapper.createObjectNode().put("offset", offset).put("append", "{\"id\":2,\"ts\":20}\n").toString());
        Files.writeString(history, "external change", StandardOpenOption.APPEND);
        String changed = Files.readString(history);
        assertThrows(IOException.class, () -> store.loadSheet(campaign.getId(), "hero"));
        assertNull(store.historyEntry(campaign.getId(), 1, 10));
        assertTrue(store.historyTail(campaign.getId(), 10).isEmpty());
        assertEquals(changed, Files.readString(history));
        assertTrue(Files.exists(folder.resolve("history-append.json")));
    }

    @Test
    void legacyUnterminatedUnicodeHistoryAndDuplicateIdentitiesRemainReadable() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Legacy lines");
        Path history = dir.resolve(campaign.getId()).resolve("history.jsonl");
        var first = mapper.createObjectNode().put("id", 1).put("ts", 10).put("text", "雪");
        var duplicate = first.deepCopy().put("text", "same legacy identity");
        Files.writeString(history, first + "\r" + duplicate);
        assertEquals(first, store.historyEntry(campaign.getId(), 1, 10));
        var third = mapper.createObjectNode().put("id", 2).put("ts", 20).put("text", "third");
        store.saveSheetsAndMessage(campaign.getId(), java.util.Map.of(), third);
        assertEquals(List.of(first, duplicate, third), store.historyTail(campaign.getId(), 10));
        var replacement = first.deepCopy().put("text", "replacement");
        store.replaceHistoryEntry(campaign.getId(), 1, 10, replacement);
        assertEquals(List.of(replacement, duplicate, third), store.historyTail(campaign.getId(), 10));
        store.deleteHistoryEntry(campaign.getId(), 1, 10);
        assertEquals(List.of(third), new CampaignStore(dir).historyTail(campaign.getId(), 10));
    }

    @Test
    void sharedCardIndexKeepsFirstMatchAndTracksEditsDeletionAndExternalChanges() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Shared cards");
        var first = mapper.createObjectNode().put("id", 1).put("ts", 10);
        first.putObject("sharedCard").put("cardId", "card-one").put("text", "first");
        var duplicate = first.deepCopy().put("id", 2).put("ts", 20);
        duplicate.withObject("/sharedCard").put("text", "duplicate");
        store.appendHistoryChecked(campaign.getId(), first);
        store.appendHistoryChecked(campaign.getId(), duplicate);
        assertEquals(first, store.historyCard(campaign.getId(), "card-one"));
        assertNull(store.historyCard(campaign.getId(), "missing"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) store.historyCard(campaign.getId(), "card-one"))
                .withObject("/sharedCard").put("text", "uncommitted");
        assertEquals(first, store.historyCard(campaign.getId(), "card-one"));
        var changed = first.deepCopy();
        changed.withObject("/sharedCard").put("cardId", "card-two");
        store.replaceHistoryEntry(campaign.getId(), 1, 10, changed);
        assertEquals(changed, store.historyCard(campaign.getId(), "card-two"));
        assertEquals(duplicate, store.historyCard(campaign.getId(), "card-one"));
        store.deleteHistoryEntry(campaign.getId(), 2, 20);
        assertNull(store.historyCard(campaign.getId(), "card-one"));
        Path history = dir.resolve(campaign.getId()).resolve("history.jsonl");
        FileTime modified = Files.getLastModifiedTime(history);
        Files.writeString(history, first + "\n");
        // Same size and inode: ensure the external edit is not hidden by coarse filesystem timestamps.
        Files.setLastModifiedTime(history, FileTime.fromMillis(modified.toMillis() + 2000));
        assertEquals(first, store.historyCard(campaign.getId(), "card-one"));
        assertNull(store.historyCard(campaign.getId(), "card-two"));
    }

    @Test
    void sheetReadCacheInvalidatesOnWritesRecoveryExternalChangesAndCampaignDeletion() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Sheet cache");
        String campaignId = campaign.getId();
        Path folder = dir.resolve(campaignId);
        Path sheet = folder.resolve("sheets").resolve("hero.json");
        store.saveSheet(campaignId, "hero", "{\"revision\":1}");
        String original = store.loadSheet(campaignId, "hero");
        assertSame(original, store.loadSheet(campaignId, "hero"));
        store.saveSheet(campaignId, "hero", "{\"revision\":2}");
        assertEquals(2, mapper.readTree(store.loadSheet(campaignId, "hero")).path("revision").asInt());
        store.saveSheets(campaignId, java.util.Map.of("hero", mapper.createObjectNode().put("revision", 3)));
        assertEquals(3, mapper.readTree(store.loadSheet(campaignId, "hero")).path("revision").asInt());
        Files.writeString(folder.resolve("sheet-transfer.json"), "{\"hero\":{\"revision\":4}}");
        assertEquals(4, mapper.readTree(store.loadSheet(campaignId, "hero")).path("revision").asInt());
        FileTime modified = Files.getLastModifiedTime(sheet);
        Files.writeString(sheet, "{\"revision\":5}");
        Files.setLastModifiedTime(sheet, FileTime.fromMillis(modified.toMillis() + 2000));
        assertEquals(5, mapper.readTree(store.loadSheet(campaignId, "hero")).path("revision").asInt());
        Path replacement = sheet.resolveSibling("external-sheet.json");
        Files.writeString(replacement, "{\"revision\":6}");
        Files.move(replacement, sheet, StandardCopyOption.REPLACE_EXISTING);
        assertEquals(6, mapper.readTree(store.loadSheet(campaignId, "hero")).path("revision").asInt());
        Files.delete(sheet);
        assertNull(store.loadSheet(campaignId, "hero"));
        Files.writeString(sheet, "{\"revision\":7}");
        assertEquals(7, mapper.readTree(store.loadSheet(campaignId, "hero")).path("revision").asInt());
        store.delete(campaignId);
        assertNull(store.loadSheet(campaignId, "hero"));
        store.saveSheet(campaignId, "hero", "{\"revision\":8}");
        assertEquals(8, mapper.readTree(store.loadSheet(campaignId, "hero")).path("revision").asInt());
    }

    @Test
    void interruptedTransferReplaysBothSheetsBeforeReadsAndSaves() throws Exception {
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Transfer");
        store.saveSheet(campaign.getId(), "source", "{\"qty\":0}");
        store.saveSheet(campaign.getId(), "target", "{\"qty\":0}");
        Path journal = dir.resolve(campaign.getId()).resolve("sheet-transfer.json");
        Files.writeString(journal, "{\"source\":{\"qty\":0},\"target\":{\"qty\":1}}");
        CampaignStore reopened = new CampaignStore(dir);
        assertEquals("{\"qty\":1}", reopened.loadSheet(campaign.getId(), "target"));
        assertEquals("{\"qty\":0}", reopened.loadSheet(campaign.getId(), "source"));
        assertTrue(Files.notExists(journal));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        reopened.transferSheets(campaign.getId(), "source", mapper.createObjectNode().put("qty", 1),
                "target", mapper.createObjectNode().put("qty", 0));
        assertEquals("{\"qty\":1}", reopened.loadSheet(campaign.getId(), "source"));
        assertEquals("{\"qty\":0}", reopened.loadSheet(campaign.getId(), "target"));
    }

    @Test
    void createStoresSystemAndListsSummaries() throws IOException {
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("  Frosthaven  ", "Daggerheart");
        campaign.getActorFolders().add("Heroes/Party");
        campaign.getActors().add(Campaign.Actor.builder().name("Mara").folder("Heroes/Party").build());
        campaign.getActors().getFirst().setTestingCharacter(true);
        store.save(campaign);

        Campaign loaded = store.load(campaign.getId());
        assertEquals("Frosthaven", loaded.getName());
        assertEquals("Daggerheart", loaded.getSystem());
        assertEquals(List.of("Heroes/Party"), loaded.getActorFolders());
        assertEquals("Heroes/Party", loaded.getActors().getFirst().getFolder());
        assertTrue(loaded.getActors().getFirst().isTestingCharacter());

        List<CampaignStore.Summary> summaries = store.list();
        assertEquals(1, summaries.size());
        assertEquals("Daggerheart", summaries.getFirst().system());
        assertEquals(1, summaries.getFirst().actors());
    }

    @Test
    void fearIsClampedAndPersisted() throws IOException {
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Fear", null);
        assertEquals(0, campaign.getFear());
        campaign.setFear(99);
        assertEquals(Campaign.MAX_FEAR, campaign.getFear());
        campaign.setFear(-3);
        assertEquals(0, campaign.getFear());
        campaign.setFear(7);
        store.save(campaign);
        assertEquals(7, store.load(campaign.getId()).getFear());
    }

    @Test
    void blankNameAndSystemFallBackToDefaults() throws IOException {
        Campaign campaign = new CampaignStore(dir).create(" ", null);
        assertEquals("New campaign", campaign.getName());
        assertEquals(Campaign.DEFAULT_SYSTEM, campaign.getSystem());
    }

    @Test
    void olderCampaignFilesWithoutSystemOrFoldersStillLoad() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("old"));
        Files.writeString(folder.resolve("campaign.json"), """
                {"id":"old","name":"Legacy","players":[{"id":"p1","name":"Alice"}],"actors":[{"id":"a1","name":"Rook"}]}
                """, StandardCharsets.UTF_8);
        CampaignStore store = new CampaignStore(dir);

        Campaign loaded = store.load("old");
        assertEquals("", loaded.player("p1").getChatIdentity());
        assertNotNull(loaded.getActorFolders());
        assertTrue(loaded.getActorFolders().isEmpty());
        assertNull(loaded.getActors().getFirst().getFolder());
        org.junit.jupiter.api.Assertions.assertFalse(loaded.getActors().getFirst().isTestingCharacter());
        assertEquals(Campaign.DEFAULT_SYSTEM, store.list().getFirst().system());
    }
}
