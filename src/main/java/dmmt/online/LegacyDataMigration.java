package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.online.automation.AutomationHost;
import dmmt.online.automation.LegacyCatalogConverter;
import dmmt.online.automation.RuleCompiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * One-time conversion of catalogs and saved campaigns: back up, convert into a staged copy, validate everything, then
 * a journaled cutover that can be resumed after an interruption or rolled back. Nothing under the live directories is
 * touched until the whole staged result validated. The tool never runs as part of normal play.
 */
public final class LegacyDataMigration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CATALOG_KEY = "catalogs/", CAMPAIGN_KEY = "campaigns/";
    private static final List<String> RENAMED_CHAT_KEYS = List.of("automationClaim", "automationRecoveryRoll");

    public enum Status { COMPLETE, ALREADY_COMPLETE, BLOCKED, ROLLED_BACK }

    public record Outcome(Status status, ObjectNode report) { }

    private final Path catalogDir, campaignsRoot, workDir;
    private final IntConsumer afterFileCutover;

    public LegacyDataMigration(Path catalogDir, Path campaignsRoot, Path workDir) {
        this(catalogDir, campaignsRoot, workDir, done -> { });
    }

    /** {@code afterFileCutover} is called with the number of files cut over so far; tests use it to simulate a crash. */
    LegacyDataMigration(Path catalogDir, Path campaignsRoot, Path workDir, IntConsumer afterFileCutover) {
        this.catalogDir = catalogDir.toAbsolutePath().normalize();
        this.campaignsRoot = campaignsRoot.toAbsolutePath().normalize();
        this.workDir = workDir.toAbsolutePath().normalize();
        this.afterFileCutover = afterFileCutover;
        for (Path live : List.of(this.catalogDir, this.campaignsRoot))
            if (this.workDir.startsWith(live) || live.startsWith(this.workDir))
                throw new IllegalArgumentException("The work directory must be separate from the catalogs and campaigns.");
    }

    private Path journalFile() { return workDir.resolve("conversion-journal.json"); }
    private Path backupDir() { return workDir.resolve("backup"); }
    private Path stagingDir() { return workDir.resolve("staging"); }

    /** Converts, or resumes an interrupted cutover, or does nothing when the conversion already completed. */
    public Outcome run() throws IOException {
        Files.createDirectories(workDir);
        ObjectNode journal = readJournal();
        if (journal != null) {
            String phase = journal.path("phase").asText();
            if ("COMPLETE".equals(phase)) return new Outcome(Status.ALREADY_COMPLETE, report(journal, List.of()));
            if ("CUTTING_OVER".equals(phase)) return cutOver(journal);
        }
        deleteTree(backupDir());
        deleteTree(stagingDir());
        Files.deleteIfExists(journalFile());
        return convert();
    }

    private Outcome convert() throws IOException {
        List<LegacySheetConverter.Diagnostic> diagnostics = new ArrayList<>();
        ObjectNode journal = JSON.createObjectNode();
        journal.put("phase", "PLANNED");
        journal.putArray("files");
        writeJournal(journal);
        backUp(journal);
        journal.put("phase", "BACKED_UP");
        writeJournal(journal);

        ObjectNode catalogReport = stageCatalogs(diagnostics);
        LegacySheetConverter.Catalogs catalogs = stagedCatalogs();
        for (Path campaign : campaigns()) stageCampaign(campaign, catalogs, diagnostics);
        journal.put("phase", "STAGED");
        writeJournal(journal);

        validate(diagnostics);
        journal.set("catalogReport", catalogReport);
        if (diagnostics.stream().anyMatch(LegacySheetConverter.Diagnostic::error)) {
            journal.put("phase", "BLOCKED");
            writeJournal(journal);
            return new Outcome(Status.BLOCKED, report(journal, diagnostics));
        }
        journal.put("phase", "VALIDATED");
        ArrayNode files = journal.putArray("files");
        for (Change change : changes()) files.add(change.toJson());
        journal.put("phase", "CUTTING_OVER");
        writeJournal(journal);
        Files.writeString(workDir.resolve("conversion-diagnostics.json"), diagnosticsJson(diagnostics).toString());
        return cutOver(journal);
    }

    /** Restores the backed-up originals (and removes files the conversion added). */
    public Outcome rollback() throws IOException {
        ObjectNode journal = readJournal();
        if (journal == null || !List.of("CUTTING_OVER", "COMPLETE").contains(journal.path("phase").asText()))
            throw new IllegalStateException("There is no converted or partially converted data to roll back.");
        for (JsonNode file : journal.path("files")) {
            Path target = target(file.path("key").asText());
            Path backup = backupDir().resolve(file.path("key").asText());
            if (Files.isRegularFile(backup)) replace(target, Files.readAllBytes(backup));
            else Files.deleteIfExists(target);
            Files.deleteIfExists(partial(target));
            if (Files.isDirectory(target.getParent()))
                try (Stream<Path> remaining = Files.list(target.getParent())) {
                    if (remaining.findAny().isEmpty()) Files.delete(target.getParent());
                }
        }
        journal.put("phase", "ROLLED_BACK");
        writeJournal(journal);
        return new Outcome(Status.ROLLED_BACK, report(journal, List.of()));
    }

    // ---- backup -------------------------------------------------------------------------------------------------

    private void backUp(ObjectNode journal) throws IOException {
        ObjectNode manifest = journal.putObject("backup");
        for (String key : liveFiles()) {
            Path source = target(key);
            Path copy = backupDir().resolve(key);
            Files.createDirectories(copy.getParent());
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
            manifest.put(key, hash(source));
        }
    }

    private List<String> liveFiles() throws IOException {
        List<String> keys = new ArrayList<>();
        for (Path file : list(catalogDir)) if (file.toString().endsWith(".json")) keys.add(CATALOG_KEY + catalogDir.relativize(file).toString().replace('\\', '/'));
        if (Files.isDirectory(catalogDir.resolve("rules")))
            for (Path file : list(catalogDir.resolve("rules"))) keys.add(CATALOG_KEY + catalogDir.relativize(file).toString().replace('\\', '/'));
        for (Path campaign : campaigns()) {
            String base = CAMPAIGN_KEY + campaign.getFileName() + "/";
            for (String fixed : List.of("campaign.json", "history.jsonl"))
                if (Files.isRegularFile(campaign.resolve(fixed))) keys.add(base + fixed);
            if (Files.isDirectory(campaign.resolve("sheets")))
                for (Path sheet : list(campaign.resolve("sheets"))) if (sheet.toString().endsWith(".json")) keys.add(base + "sheets/" + sheet.getFileName());
        }
        keys.removeIf(key -> !Files.isRegularFile(target(key)));
        return keys;
    }

    // ---- staging ------------------------------------------------------------------------------------------------

    private ObjectNode stageCatalogs(List<LegacySheetConverter.Diagnostic> diagnostics) throws IOException {
        Path staged = stagingDir().resolve("catalogs");
        List<Path> catalogFiles = new ArrayList<>();
        boolean legacy = false;
        for (Path file : list(catalogDir)) {
            if (!file.toString().endsWith(".json")) continue;
            JsonNode parsed = JSON.readTree(file.toFile());
            if (!"dmmap-daggerheart".equals(parsed.path("format").asText())) continue;
            catalogFiles.add(file);
            legacy |= parsed.path("version").asInt() < RuleCompiler.FORMAT_VERSION;
        }
        ObjectNode report;
        if (legacy) {
            try {
                report = LegacyCatalogConverter.convertDirectory(catalogDir, staged);
            } catch (RuntimeException | IOException e) {
                diagnostics.add(new LegacySheetConverter.Diagnostic("", "catalogs", true,
                        "The catalogs cannot be converted: " + e.getMessage() + " Fix the catalog and run the conversion again."));
                return JSON.createObjectNode();
            }
            Files.deleteIfExists(staged.resolve("conversion-report.json"));
        } else {
            report = JSON.createObjectNode();
            Files.createDirectories(staged);
            for (Path file : catalogFiles) Files.copy(file, staged.resolve(file.getFileName()));
            Path rules = catalogDir.resolve("rules");
            if (Files.isDirectory(rules)) {
                Files.createDirectories(staged.resolve("rules"));
                for (Path file : list(rules)) Files.copy(file, staged.resolve("rules").resolve(file.getFileName()));
            }
        }
        for (Path file : list(staged)) {
            if (!file.toString().endsWith(".json")) continue;
            ObjectNode doc = (ObjectNode) JSON.readTree(file.toFile());
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(CatalogEditor.editable(doc)), StandardCharsets.UTF_8);
        }
        return report;
    }

    private LegacySheetConverter.Catalogs stagedCatalogs() throws IOException {
        LegacySheetConverter.Catalogs catalogs = new LegacySheetConverter.Catalogs();
        Path staged = stagingDir().resolve("catalogs");
        if (!Files.isDirectory(staged)) return catalogs;
        for (Path file : list(staged))
            if (file.toString().endsWith(".json")) catalogs.add(JSON.readTree(file.toFile()));
        return catalogs;
    }

    private void stageCampaign(Path campaign, LegacySheetConverter.Catalogs catalogs, List<LegacySheetConverter.Diagnostic> diagnostics) throws IOException {
        String name = campaign.getFileName().toString();
        for (String pending : List.of("sheet-transfer.json", "history-append.json"))
            if (Files.exists(campaign.resolve(pending)))
                diagnostics.add(new LegacySheetConverter.Diagnostic("", CAMPAIGN_KEY + name + "/" + pending, true,
                        "The campaign '" + name + "' has an unfinished save. Open it once in the application to recover it, close the application and run the conversion again."));
        Path sheets = campaign.resolve("sheets");
        if (Files.isDirectory(sheets)) {
            for (Path file : list(sheets)) {
                if (!file.toString().endsWith(".json")) continue;
                String actorId = file.getFileName().toString().replaceAll("\\.json$", "");
                String path = CAMPAIGN_KEY + name + "/sheets/" + file.getFileName();
                JsonNode legacy = JSON.readTree(file.toFile());
                if (!(legacy instanceof ObjectNode sheet)) {
                    diagnostics.add(new LegacySheetConverter.Diagnostic(actorId, path, true, "The saved sheet is not a JSON object."));
                    continue;
                }
                List<LegacySheetConverter.Diagnostic> found = new ArrayList<>();
                ObjectNode converted = LegacySheetConverter.convert(sheet, actorId, catalogs, found);
                for (LegacySheetConverter.Diagnostic d : found)
                    diagnostics.add(new LegacySheetConverter.Diagnostic(d.actorId(), path + d.path(), d.error(), "Campaign '" + name + "': " + d.message()));
                if (!converted.equals(sheet)) stage(path, converted.toString());
            }
        }
        Path history = campaign.resolve("history.jsonl");
        if (Files.isRegularFile(history)) {
            StringBuilder out = new StringBuilder();
            boolean changed = false;
            for (String line : Files.readAllLines(history, StandardCharsets.UTF_8)) {
                String next = line;
                if (!line.isBlank() && (line.contains("automationClaim") || line.contains("automationRecoveryRoll"))) {
                    try {
                        JsonNode message = JSON.readTree(line);
                        if (renameClaims(message)) next = message.toString();
                    } catch (IOException e) {
                        diagnostics.add(new LegacySheetConverter.Diagnostic("", CAMPAIGN_KEY + name + "/history.jsonl", false,
                                "A chat line could not be parsed and was kept unchanged."));
                    }
                }
                changed |= !next.equals(line);
                out.append(next).append('\n');
            }
            if (changed) stage(CAMPAIGN_KEY + name + "/history.jsonl", out.toString());
        }
    }

    /** Historic claims stay visible but lose the keys the live application executes. */
    private static boolean renameClaims(JsonNode node) {
        boolean changed = false;
        if (node instanceof ObjectNode object) {
            for (String key : RENAMED_CHAT_KEYS) {
                if (!object.has(key)) continue;
                String legacyKey = "legacy" + key.substring("automation".length());
                if (!object.has(legacyKey)) object.set(legacyKey, object.remove(key));
                else object.remove(key);
                changed = true;
            }
            for (JsonNode child : object) changed |= renameClaims(child);
        } else if (node.isArray()) for (JsonNode child : node) changed |= renameClaims(child);
        return changed;
    }

    private void stage(String key, String content) throws IOException {
        Path file = stagingDir().resolve(key);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    // ---- validation ---------------------------------------------------------------------------------------------

    /** Revalidates the whole staged pack and re-converts every staged sheet, which must then be unchanged and error free. */
    private void validate(List<LegacySheetConverter.Diagnostic> diagnostics) throws IOException {
        if (diagnostics.stream().noneMatch(d -> d.path().equals("catalogs") && d.error())) {
            Path staged = stagingDir().resolve("catalogs");
            Map<String, String> pack = new LinkedHashMap<>();
            for (Path file : list(staged)) {
                if (!file.toString().endsWith(".json")) continue;
                JsonNode parsed = JSON.readTree(file.toFile());
                if ("dmmap-daggerheart".equals(parsed.path("format").asText())) pack.put(parsed.path("category").asText(), Files.readString(file));
            }
            try {
                Path rules = staged.resolve("rules");
                String recipes = Files.isRegularFile(rules.resolve("recipes.json")) ? Files.readString(rules.resolve("recipes.json")) : LegacyCatalogConverter.coreRecipes();
                String ruleset = Files.isRegularFile(rules.resolve("ruleset.json")) ? Files.readString(rules.resolve("ruleset.json")) : LegacyCatalogConverter.coreRuleset();
                AutomationHost.compile("conversion-pack", pack, recipes, ruleset);
            } catch (RuntimeException e) {
                diagnostics.add(new LegacySheetConverter.Diagnostic("", "catalogs", true, "The converted catalogs do not validate: " + e.getMessage()));
            }
        }
        LegacySheetConverter.Catalogs catalogs = stagedCatalogs();
        for (Path campaign : campaigns()) {
            String name = campaign.getFileName().toString();
            Path sheets = campaign.resolve("sheets");
            if (!Files.isDirectory(sheets)) continue;
            for (Path file : list(sheets)) {
                if (!file.toString().endsWith(".json")) continue;
                Path staged = stagingDir().resolve(CAMPAIGN_KEY + name + "/sheets/" + file.getFileName());
                String sheetPath = CAMPAIGN_KEY + name + "/sheets/" + file.getFileName();
                if (diagnostics.stream().anyMatch(d -> d.error() && d.path().startsWith(sheetPath))) continue;
                Path source = Files.isRegularFile(staged) ? staged : file;
                if (!(JSON.readTree(source.toFile()) instanceof ObjectNode sheet)) continue;
                List<LegacySheetConverter.Diagnostic> again = new ArrayList<>();
                ObjectNode second = LegacySheetConverter.convert(sheet, file.getFileName().toString().replaceAll("\\.json$", ""), catalogs, again);
                if (again.stream().anyMatch(LegacySheetConverter.Diagnostic::error) || !second.equals(sheet))
                    diagnostics.add(new LegacySheetConverter.Diagnostic(file.getFileName().toString(), CAMPAIGN_KEY + name + "/sheets/" + file.getFileName(), true,
                            "The converted sheet is not stable under a second conversion; it was not activated."));
            }
        }
    }

    // ---- cutover ------------------------------------------------------------------------------------------------

    private record Change(String key, String before, String after) {
        ObjectNode toJson() {
            ObjectNode node = JSON.createObjectNode().put("key", key).put("done", false);
            if (before != null) node.put("before", before);
            return node.put("after", after);
        }
    }

    private List<Change> changes() throws IOException {
        List<Change> changes = new ArrayList<>();
        Path staged = stagingDir();
        if (!Files.isDirectory(staged)) return changes;
        try (Stream<Path> walk = Files.walk(staged)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String key = staged.relativize(file).toString().replace('\\', '/');
                Path live = target(key);
                String after = hash(file);
                String before = Files.isRegularFile(live) ? hash(live) : null;
                if (!after.equals(before)) changes.add(new Change(key, before, after));
            }
        }
        return changes;
    }

    private Outcome cutOver(ObjectNode journal) throws IOException {
        ArrayNode files = (ArrayNode) journal.path("files");
        int done = 0;
        for (JsonNode node : files) {
            ObjectNode file = (ObjectNode) node;
            if (file.path("done").asBoolean()) { done++; continue; }
            String key = file.path("key").asText();
            Path live = target(key);
            String current = Files.isRegularFile(live) ? hash(live) : null;
            if (!file.path("after").asText().equals(current)) {
                if (!java.util.Objects.equals(current, file.path("before").isMissingNode() ? null : file.path("before").asText()))
                    throw new IOException("'" + live + "' changed after the backup was taken. Close the application and restore the backup, or roll back, before converting again.");
                replace(live, Files.readAllBytes(stagingDir().resolve(key)));
            }
            file.put("done", true);
            writeJournal(journal);
            afterFileCutover.accept(++done);
        }
        journal.put("phase", "COMPLETE");
        writeJournal(journal);
        return new Outcome(Status.COMPLETE, report(journal, List.of()));
    }

    // ---- files --------------------------------------------------------------------------------------------------

    private Path target(String key) {
        if (key.startsWith(CATALOG_KEY)) return catalogDir.resolve(key.substring(CATALOG_KEY.length()));
        if (key.startsWith(CAMPAIGN_KEY)) return campaignsRoot.resolve(key.substring(CAMPAIGN_KEY.length()));
        throw new IllegalArgumentException("Unknown file key " + key);
    }

    private static Path partial(Path target) { return target.resolveSibling(target.getFileName() + ".converting"); }

    private static void replace(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = partial(target);
        Files.write(temp, content);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<Path> campaigns() throws IOException {
        if (!Files.isDirectory(campaignsRoot)) return List.of();
        return list(campaignsRoot).stream().filter(p -> Files.isRegularFile(p.resolve("campaign.json"))).toList();
    }

    private static List<Path> list(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> children = Files.list(directory)) {
            return children.filter(p -> Files.isRegularFile(p) || Files.isDirectory(p)).sorted().filter(p -> !p.getFileName().toString().endsWith(".converting")).toList();
        }
    }

    private static String hash(Path file) throws IOException { return CampaignStore.sha256(Files.readAllBytes(file)); }

    private ObjectNode readJournal() throws IOException {
        return Files.isRegularFile(journalFile()) ? (ObjectNode) JSON.readTree(journalFile().toFile()) : null;
    }

    private void writeJournal(ObjectNode journal) throws IOException {
        Path temp = journalFile().resolveSibling("conversion-journal.json.tmp");
        Files.writeString(temp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(journal), StandardCharsets.UTF_8);
        try {
            Files.move(temp, journalFile(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, journalFile(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static ArrayNode diagnosticsJson(List<LegacySheetConverter.Diagnostic> diagnostics) {
        ArrayNode array = JSON.createArrayNode();
        for (LegacySheetConverter.Diagnostic d : diagnostics)
            array.addObject().put("actor", d.actorId()).put("path", d.path()).put("severity", d.error() ? "error" : "note").put("message", d.message());
        return array;
    }

    private ObjectNode report(ObjectNode journal, List<LegacySheetConverter.Diagnostic> diagnostics) throws IOException {
        ObjectNode report = JSON.createObjectNode();
        report.put("phase", journal.path("phase").asText());
        report.put("backup", backupDir().toString());
        ArrayNode changed = report.putArray("changed");
        for (JsonNode file : journal.path("files")) changed.add(file.path("key").asText());
        report.set("diagnostics", diagnosticsJson(diagnostics));
        if (journal.has("catalogReport")) report.set("catalogs", journal.get("catalogReport"));
        Files.writeString(workDir.resolve("conversion-report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        return report;
    }

    /** {@code convert|rollback <catalog-dir> <campaigns-dir> <work-dir>}. */
    public static void main(String[] args) throws IOException {
        if (args.length != 4 || !List.of("convert", "rollback").contains(args[0]))
            throw new IllegalArgumentException("Usage: LegacyDataMigration <convert|rollback> <catalog-dir> <campaigns-dir> <work-dir>");
        LegacyDataMigration migration = new LegacyDataMigration(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
        Outcome outcome = "convert".equals(args[0]) ? migration.run() : migration.rollback();
        System.out.println("Conversion " + outcome.status() + ". Report: " + Path.of(args[3]).toAbsolutePath().resolve("conversion-report.json"));
        outcome.report().path("diagnostics").forEach(d -> {
            if ("error".equals(d.path("severity").asText())) System.out.println("ERROR " + d.path("path").asText() + ": " + d.path("message").asText());
        });
        if (outcome.status() == Status.BLOCKED) System.exit(2);
    }
}
