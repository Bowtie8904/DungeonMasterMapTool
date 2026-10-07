package dmmt.ui;

import dmmt.service.AppSettings;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Keeps controls alive while removing hidden controls and their layout space. */
public final class ControlVisibility {
    private final Map<Node, String> controls = new IdentityHashMap<>();
    private final Set<Pane> containers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Node> decorations = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    public void register(String id, Node... nodes) {
        if (AppSettings.SIDEBAR_CONTROLS.stream().noneMatch(c -> c.id().equals(id))) {
            throw new IllegalArgumentException("Unknown DM control: " + id);
        }
        for (Node node : nodes) {
            if (controls.putIfAbsent(node, id) != null) {
                throw new IllegalArgumentException("DM control node already registered: " + id);
            }
        }
    }

    /** Repeated ids include labels/readouts; null marks decoration, empty marks a registered nested row. */
    public void registerRow(String section, Pane row, String... ids) {
        if (row.getChildren().size() != ids.length) {
            throw new IllegalArgumentException("DM control row does not match its visibility ids: " + section);
        }
        containers.add(row);
        for (int i = 0; i < ids.length; i++) {
            Node child = row.getChildren().get(i);
            if (ids[i] == null) {
                decorations.add(child);
            } else if (ids[i].isEmpty()) {
                if (!(child instanceof Pane pane) || !containers.contains(pane)) {
                    throw new IllegalArgumentException("Unregistered nested DM control row: " + section);
                }
            } else {
                register(section + "." + ids[i], child);
            }
        }
    }

    public void registerContainer(Pane container) {
        containers.add(container);
    }

    public Set<String> registeredIds() {
        return new LinkedHashSet<>(controls.values());
    }

    public void apply(Set<String> hidden) {
        controls.forEach((node, id) -> show(node, !hidden.contains(id)));
        for (Pane container : containers) {
            refresh(container);
        }
    }

    private void refresh(Pane container) {
        for (Node child : container.getChildren()) {
            if (child instanceof Pane pane && containers.contains(pane)) {
                refresh(pane);
            }
        }
        boolean any = container.getChildren().stream()
                .anyMatch(n -> !decorations.contains(n) && n.isVisible() && n.isManaged());
        boolean contentSinceSeparator = false;
        for (int i = 0; i < container.getChildren().size(); i++) {
            Node child = container.getChildren().get(i);
            if (!decorations.contains(child)) {
                contentSinceSeparator |= child.isVisible() && child.isManaged();
                continue;
            }
            boolean after = container.getChildren().subList(i + 1, container.getChildren().size()).stream()
                    .anyMatch(n -> !decorations.contains(n) && n.isVisible() && n.isManaged());
            boolean visible = child instanceof Label ? any : contentSinceSeparator && after;
            show(child, visible);
            if (visible && child.getStyleClass().contains("vsep")) {
                contentSinceSeparator = false;
            }
        }
        show(container, any);
    }

    private static void show(Node node, boolean show) {
        node.setVisible(show);
        node.setManaged(show);
    }
}
