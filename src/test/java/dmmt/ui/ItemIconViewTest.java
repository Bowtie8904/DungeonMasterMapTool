package dmmt.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.FxTestSupport;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ItemIconViewTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void rendersEverySelectedSvgNativelyAtCompactSizeAndInDesktopChat() throws Exception {
        Set<String> images = new HashSet<>();
        for (String category : List.of("armor", "weapons", "items", "consumables"))
            for (var entry : MAPPER.readTree(Path.of("dmmap-daggerheart", category + ".json").toFile()).path("entries"))
                images.add(entry.path("image").asText());
        assertFalse(images.isEmpty());
        onFx(() -> {
            for (String image : images) {
                var icon = assertInstanceOf(StackPane.class, ItemIconView.create(image), image);
                assertEquals(28, icon.getPrefWidth(), image);
                assertEquals(28, icon.getPrefHeight(), image);
                assertTrue(icon.getChildren().getFirst().getBoundsInParent().getWidth() <= 28.1, image);
                icon.resize(28, 28);
                icon.layout();
                var bounds = icon.getChildren().getFirst().getBoundsInParent();
                assertTrue(bounds.getMinX() >= -0.1 && bounds.getMinY() >= -0.1, image + " " + bounds);
                assertTrue(bounds.getMaxX() <= 28.1 && bounds.getMaxY() <= 28.1, image + " " + bounds);
            }
            String image = images.iterator().next();
            ObjectNode message = MAPPER.createObjectNode().put("type", "chat").put("from", "Hero");
            ObjectNode shared = message.putObject("sharedCard").put("category", "item").put("actorName", "Hero");
            shared.putObject("item").put("name", "Potion").put("image", image).put("type", "consumable");
            var card = ChatCards.render(message);
            Label name = (Label) card.lookup(".chat-shared-name");
            assertNotNull(name);
            assertNotNull(name.getGraphic());
            assertEquals("Potion", name.getText());
            assertNull(ItemIconView.create(""));
            assertInstanceOf(Label.class, ItemIconView.create("https://example.org/image.svg"));
        });
    }

    private static void onFx(Runnable runnable) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { runnable.run(); return null; });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }
}
