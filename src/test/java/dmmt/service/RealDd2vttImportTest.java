package dmmt.service;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealDd2vttImportTest {
    @TempDir
    Path tempDir;

    @Test
    void importsProjectRootDd2vttFile() throws Exception {
        Path repoRoot = Path.of(System.getProperty("user.dir"));
        List<Path> dd2vttFiles;
        try (Stream<Path> stream = Files.list(repoRoot)) {
            dd2vttFiles = stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".dd2vtt"))
                    .collect(Collectors.toList());
        }

        assertFalse(dd2vttFiles.isEmpty(), "No .dd2vtt file found in project root.");
        Path source = dd2vttFiles.get(0);
        Path projectDir = tempDir.resolve("imported-project");
        Files.createDirectories(projectDir);

        Dd2vttImportService service = new Dd2vttImportService();
        DmProject project = service.importToProject(source, projectDir);

        assertNotNull(project.getMap().getImagePath(), "Image path should be set after import.");
        assertNull(project.getMap().getSourcePath(), "The dd2vtt file must not be stored in the project.");
        assertFalse(project.getWalls().isEmpty(), "Expected line-of-sight walls from DD2VTT.");
        assertFalse(project.getInteractables().isEmpty(), "Expected portals/interactables from DD2VTT.");
        assertFalse(project.getLighting().getLights().isEmpty(), "Expected lights from DD2VTT.");
        for (DmProject.LightSource light : project.getLighting().getLights()) {
            assertEquals(DmProject.RevealMode.NONE, light.getRevealMode(), "Map lamps must not reveal fog.");
            assertTrue(light.getColor().matches("#[0-9A-Fa-f]{6}"), "Unexpected light color " + light.getColor());
        }
        assertNotNull(project.getFog().getMask(), "Imported project should have a fog mask.");
        assertEquals("#FFEDCF", Dd2vttImportService.parseDd2vttColor("ffFFEDCF").toUpperCase());
    }
}
