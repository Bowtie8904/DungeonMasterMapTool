package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.online.Campaign;
import dmmt.online.OnlineSessionController;
import dmmt.online.OnlineSessionServer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OnlinePlayPanelTest {
    @TempDir Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void connectedTableFormatsAndWrapsPlayerIdentities() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            var actions = new OnlinePlayPanel.Actions() {
                @Override public void placeActor(Campaign campaign, Campaign.Actor actor) { }
                @Override public void openSheet(Campaign campaign, Campaign.Actor actor) { }
                @Override public int foreignActorTokens(Campaign campaign) { return 0; }
                @Override public void removeForeignActorTokens(Campaign campaign) { }
            };
            var panel = new OnlinePlayPanel(new OnlineSessionController(temp, null), actions);
            TabPane tabs = (TabPane) panel.getChildren().get(1);
            Tab connected = tabs.getTabs().stream().filter(tab -> tab.getText().equals("Connected"))
                    .findFirst().orElseThrow();
            tabs.getSelectionModel().select(connected);
            VBox page = (VBox) connected.getContent();
            @SuppressWarnings("unchecked")
            TableView<OnlineSessionServer.RosterEntry> table = (TableView<OnlineSessionServer.RosterEntry>)
                    page.getChildren().stream().filter(TableView.class::isInstance).findFirst().orElseThrow();
            TableColumn<OnlineSessionServer.RosterEntry, ?> name = table.getColumns().getFirst();
            String character = "A very long character name for a narrow sidebar";
            var entry = new OnlineSessionServer.RosterEntry("p1", "Alice", 1, 42,
                    character, "#2979ff", "they/them");
            table.getItems().setAll(entry);
            String expected = "Alice (" + character + ")[they/them]";
            assertEquals(expected, name.getCellObservableValue(0).getValue());
            assertNotEquals(28, table.getFixedCellSize(), "Long roster names must not use a fixed-height row");

            Stage stage = new Stage();
            try {
                Scene scene = new Scene(panel, 320, 600);
                scene.getStylesheets().add(OnlinePlayPanel.class.getResource("/dmmt/ui/dark.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                panel.applyCss();
                panel.layout();
                TableCell<?, ?> nameCell = table.lookupAll(".table-cell").stream()
                        .filter(TableCell.class::isInstance).map(TableCell.class::cast)
                        .filter(cell -> expected.equals(cell.getText())).findFirst().orElseThrow();
                assertTrue(nameCell.isWrapText());
                assertTrue(nameCell.getHeight() > 28, "Long names must wrap instead of being clipped");
                assertTrue(nameCell.getHeight() >= nameCell.prefHeight(nameCell.getWidth()) - 1,
                        "The complete wrapped name must fit in the row");
                assertEquals(expected, ((Text) nameCell.lookup(".text")).getText(),
                        "The displayed name must not be ellipsized");
                double narrowHeight = nameCell.getHeight();
                name.setPrefWidth(180);
                panel.layout();
                assertTrue(nameCell.getHeight() < narrowHeight, "Rows shrink when the player column gets wider");
                name.setPrefWidth(95);
                panel.layout();
                assertTrue(nameCell.getHeight() >= nameCell.prefHeight(nameCell.getWidth()) - 1,
                        "Rows grow when the player column gets narrower");
                double longHeight = nameCell.getHeight();

                table.getItems().setAll(new OnlineSessionServer.RosterEntry("p1", "Alice", 1, 17,
                        "New character", "#2979ff"));
                assertEquals("Alice (New character)", name.getCellObservableValue(0).getValue());
                panel.layout();
                TableCell<?, ?> updatedCell = table.lookupAll(".table-cell").stream()
                        .filter(TableCell.class::isInstance).map(TableCell.class::cast)
                        .filter(cell -> "Alice (New character)".equals(cell.getText())).findFirst().orElseThrow();
                assertTrue(updatedCell.getHeight() < longHeight, "Rows shrink when an identity gets shorter");
                assertTrue(updatedCell.getHeight() >= updatedCell.prefHeight(updatedCell.getWidth()) - 1,
                        "A refreshed identity must fit in the row");
                table.getItems().setAll(new OnlineSessionServer.RosterEntry("p1", "Alice", 1, 17,
                        "", "#2979ff", "they/them"));
                assertEquals("Alice", name.getCellObservableValue(0).getValue());

                String unbrokenCharacter = "LongCharacterName".repeat(12);
                String unbrokenName = "Alice (" + unbrokenCharacter + ")[they/them]";
                table.getItems().setAll(new OnlineSessionServer.RosterEntry("p1", "Alice", 1, 17,
                        unbrokenCharacter, "#2979ff", "they/them"));
                panel.layout();
                TableCell<?, ?> unbrokenCell = table.lookupAll(".table-cell").stream()
                        .filter(TableCell.class::isInstance).map(TableCell.class::cast)
                        .filter(cell -> unbrokenName.equals(cell.getText())).findFirst().orElseThrow();
                assertTrue(unbrokenCell.getHeight() > 28, "Long unbroken names must wrap");
                assertEquals(unbrokenName, ((Text) unbrokenCell.lookup(".text")).getText(),
                        "Long unbroken names must not be ellipsized");
            } finally {
                stage.close();
            }
            return null;
        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }
}
