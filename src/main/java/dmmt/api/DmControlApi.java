package dmmt.api;

import dmmt.service.AppSettings;
import dmmt.ui.ControlVisibility;
import dmmt.ui.ControlKeyImages;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Live adapters for existing DM controls. All methods are called on the JavaFX application thread. */
public final class DmControlApi {
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Set<Node> menuTargets = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Node, ContextMenu> menus = new IdentityHashMap<>();
    private final Set<Node> imageMenuTargets = Collections.newSetFromMap(new IdentityHashMap<>());
    private Supplier<String> menuBaseUrl;
    private Consumer<String> menuCopiedStatus;
    private ControlKeyImages keyImages;
    private Consumer<String> keyImageBrowser;
    private Consumer<String> keyImageStatus;

    public DmControlApi(ControlVisibility registry) {
        registry.registeredNodes().forEach(this::register);
    }

    /** Adds controls outside the sidebar visibility registry, such as header/status buttons. */
    public void add(String id, Node node) {
        if (id == null || !id.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) {
            throw new IllegalArgumentException("Invalid control id: " + id);
        }
        if (entries.containsKey(id)) {
            throw new IllegalArgumentException("Control already registered: " + id);
        }
        register(id, List.of(Objects.requireNonNull(node)));
        if (!entries.containsKey(id)) {
            throw new IllegalArgumentException("No supported command control: " + id);
        }
    }

    /**
     * Replaces every control whose id is {@code prefix} or starts with {@code prefix + "."}. The audio endpoints
     * use this: their per-category and per-effect toggles change whenever the library changes (3.35.6).
     */
    public void replaceGroup(String prefix, Map<String, Node> controls) {
        Objects.requireNonNull(prefix, "prefix");
        List<Node> dropped = new ArrayList<>();
        for (Entry entry : List.copyOf(entries.values())) {
            if (entry.id().equals(prefix) || entry.id().startsWith(prefix + ".")) {
                entries.remove(entry.id());
                dropped.add(entry.control());
                dropped.addAll(entry.targets());
            }
        }
        controls.forEach(this::add);
        // Only nodes that are gone for good lose their menus; a node that was registered again keeps its menu
        // instead of collecting a second copy of every "Copy API URL" item.
        dropped.forEach(this::forget);
        if (menuBaseUrl != null) {
            attachUrlMenus(menuBaseUrl, menuCopiedStatus);
        }
        if (keyImages != null) {
            attachKeyImageMenus(keyImages, keyImageBrowser, keyImageStatus);
        }
    }

    private void forget(Node node) {
        if (entries.values().stream().anyMatch(other -> other.control() == node || other.targets().contains(node))) {
            return;
        }
        menuTargets.remove(node);
        menus.remove(node);
        imageMenuTargets.remove(node);
    }

    private void register(String id, List<Node> targets) {        Set<Node> found = Collections.newSetFromMap(new IdentityHashMap<>());
        targets.forEach(node -> collect(node, found));
        if (found.isEmpty()) {
            return;
        }
        if (found.size() != 1) {
            throw new IllegalArgumentException("Ambiguous command control: " + id);
        }
        Node control = found.iterator().next();
        entries.put(id, new Entry(id, control, targets));
    }

    private static void collect(Node node, Set<Node> result) {
        if (node instanceof ButtonBase || node instanceof Slider || node instanceof ColorPicker
                || node instanceof ComboBox<?> || supportedSpinner(node)) {
            result.add(node);
        } else if (node instanceof Parent parent && !(node instanceof Control)) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, result));
        }
    }

    private static boolean supportedSpinner(Node node) {
        return node instanceof Spinner<?> spinner
                && (spinner.getValueFactory() instanceof SpinnerValueFactory.IntegerSpinnerValueFactory
                || spinner.getValueFactory() instanceof SpinnerValueFactory.DoubleSpinnerValueFactory);
    }

    public List<Map<String, Object>> describe() {
        return entries.values().stream().map(this::describe).toList();
    }

    public Map<String, Object> execute(String id, Map<String, String> params) {
        Entry entry = entries.get(id);
        if (entry == null) {
            throw error(404, "Unknown command control: " + id);
        }
        Objects.requireNonNull(params, "params");
        Node node = entry.control();
        if (node.isDisabled()) {
            throw error(409, "Control is disabled: " + id);
        }
        if (node instanceof ButtonBase button) {
            if (!params.isEmpty()) {
                throw error(400, "Buttons and toggles do not accept parameters.");
            }
            // fire() includes ToggleButton/CheckBox state changes and their existing action handlers.
            button.fire();
        } else if (node instanceof Slider slider) {
            if (slider.isValueChanging()) {
                throw error(409, "Finish the current slider drag before sending a command.");
            }
            double value = numericValue(params, slider.getValue(), slider.getMin(), slider.getMax(), false);
            updateSlider(slider, value);
        } else if (node instanceof Spinner<?> spinner) {
            if (spinner.getValueFactory() instanceof SpinnerValueFactory.IntegerSpinnerValueFactory factory) {
                double value = numericValue(params, factory.getValue(), factory.getMin(), factory.getMax(), true);
                factory.setValue((int) value);
            } else if (spinner.getValueFactory() instanceof SpinnerValueFactory.DoubleSpinnerValueFactory factory) {
                factory.setValue(numericValue(params, factory.getValue(), factory.getMin(), factory.getMax(), false));
            } else {
                throw error(422, "Unsupported spinner value factory.");
            }
            syncSpinnerEditor(spinner);
        } else if (node instanceof ColorPicker picker) {
            String value = valueParameter(params);
            if (!value.matches("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?")) {
                throw error(400, "Color must be #RRGGBB or #RRGGBBAA.");
            }
            picker.setValue(Color.web(value));
            picker.fireEvent(new ActionEvent());
        } else if (node instanceof ComboBox<?> combo) {
            select(combo, params);
        } else {
            throw error(422, "Unsupported command control: " + id);
        }
        return describe(entry);
    }

    private static <T> void select(ComboBox<T> combo, Map<String, String> params) {
        T selected;
        if (params.size() == 1 && params.containsKey("index")) {
            String text = params.get("index");
            int index;
            try {
                if (text == null || !text.matches("\\d+")) {
                    throw new NumberFormatException();
                }
                index = Integer.parseInt(text);
            } catch (NumberFormatException ex) {
                throw error(400, "Dropdown index must be a nonnegative integer.");
            }
            if (index >= combo.getItems().size()) {
                throw error(400, "Dropdown index is outside the available choices.");
            }
            selected = combo.getItems().get(index);
        } else {
            String value = valueParameter(params);
            List<T> matches = combo.getItems().stream().filter(item -> display(combo, item).equals(value)).toList();
            if (matches.size() != 1) {
                throw error(400, "Unknown or ambiguous dropdown choice: " + value);
            }
            selected = matches.getFirst();
        }
        // A skinned ComboBox emits its action from the skin's value listener; an unskinned
        // control does not. Deliver exactly one action in either case.
        boolean[] actionFired = {false};
        EventHandler<ActionEvent> observer = event -> actionFired[0] = true;
        combo.addEventFilter(ActionEvent.ACTION, observer);
        try {
            combo.setValue(selected);
            if (!actionFired[0]) {
                combo.fireEvent(new ActionEvent());
            }
        } finally {
            combo.removeEventFilter(ActionEvent.ACTION, observer);
        }
    }

    private static <T> String display(ComboBox<T> combo, T value) {
        StringConverter<T> converter = combo.getConverter();
        return Objects.toString(converter == null ? value : converter.toString(value), "");
    }

    private static <T> void syncSpinnerEditor(Spinner<T> spinner) {
        SpinnerValueFactory<T> factory = spinner.getValueFactory();
        StringConverter<T> converter = factory.getConverter();
        spinner.getEditor().setText(converter == null ? Objects.toString(factory.getValue(), "")
                : converter.toString(factory.getValue()));
    }

    private static String valueParameter(Map<String, String> params) {
        if (params.size() != 1 || !params.containsKey("value") || params.get("value") == null) {
            throw error(400, "Exactly one value parameter is required.");
        }
        return params.get("value");
    }

    private static double numericValue(Map<String, String> params, double current, double min, double max,
                                       boolean integer) {
        if (params.size() != 1) {
            throw error(400, "Use exactly one of value, increment or decrement.");
        }
        String operation = params.keySet().iterator().next();
        if (!Set.of("value", "increment", "decrement").contains(operation)) {
            throw error(400, "Unknown numeric parameter: " + operation);
        }
        String text = params.get(operation);
        if (text == null || !text.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")) {
            throw error(400, "Expected a finite decimal number.");
        }
        double amount;
        try {
            amount = Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            throw error(400, "Invalid number.");
        }
        if (!Double.isFinite(amount) || integer && amount != Math.rint(amount)) {
            throw error(400, integer ? "Expected a finite integer." : "Expected a finite number.");
        }
        if (!operation.equals("value") && amount < 0) {
            throw error(400, "Increment/decrement amounts cannot be negative.");
        }
        double result = switch (operation) {
            case "increment" -> current + amount;
            case "decrement" -> current - amount;
            default -> amount;
        };
        return Math.max(min, Math.min(max, result));
    }

    private static void updateSlider(Slider slider, double value) {
        // Invoke only the control's gesture hooks, not its skin or ancestors: a synthesized pointer
        // event dispatched normally could change the slider from coordinates or trigger canvas tools.
        notifyMouse(slider.getOnMousePressed(), slider, MouseEvent.MOUSE_PRESSED);
        slider.setValueChanging(true);
        try {
            slider.setValue(value);
        } finally {
            slider.setValueChanging(false);
            notifyMouse(slider.getOnMouseReleased(), slider, MouseEvent.MOUSE_RELEASED);
        }
    }

    private static void notifyMouse(EventHandler<? super MouseEvent> handler, Slider slider,
                                     javafx.event.EventType<MouseEvent> type) {
        if (handler != null) {
            handler.handle(new MouseEvent(slider, slider, type, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                    false, false, false, false, type == MouseEvent.MOUSE_PRESSED,
                    false, false, false, false, true, null));
        }
    }

    private Map<String, Object> describe(Entry entry) {
        Node node = entry.control();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", entry.id());
        state.put("path", path(entry.id()));
        state.put("label", AppSettings.SIDEBAR_CONTROLS.stream().filter(c -> c.id().equals(entry.id()))
                .map(AppSettings.SidebarControl::label).findFirst().orElse(entry.id()));
        state.put("disabled", node.isDisabled());
        if (node instanceof ToggleButton toggle) {
            state.put("type", "toggle");
            state.put("value", toggle.isSelected());
        } else if (node instanceof CheckBox check) {
            state.put("type", "toggle");
            state.put("value", check.isSelected());
        } else if (node instanceof ButtonBase) {
            state.put("type", "button");
        } else if (node instanceof Slider slider) {
            state.put("type", "slider");
            state.put("value", slider.getValue());
            state.put("min", slider.getMin());
            state.put("max", slider.getMax());
            state.put("step", Math.min(slider.getBlockIncrement(), (slider.getMax() - slider.getMin()) / 10));
        } else if (node instanceof Spinner<?> spinner) {
            state.put("type", "spinner");
            state.put("value", spinner.getValue());
            if (spinner.getValueFactory() instanceof SpinnerValueFactory.IntegerSpinnerValueFactory factory) {
                state.put("min", factory.getMin());
                state.put("max", factory.getMax());
                state.put("step", factory.getAmountToStepBy());
            } else if (spinner.getValueFactory() instanceof SpinnerValueFactory.DoubleSpinnerValueFactory factory) {
                state.put("min", factory.getMin());
                state.put("max", factory.getMax());
                state.put("step", factory.getAmountToStepBy());
            }
        } else if (node instanceof ColorPicker picker) {
            state.put("type", "color");
            state.put("value", color(picker.getValue()));
        } else if (node instanceof ComboBox<?> combo) {
            describeCombo(state, combo);
        }
        return Collections.unmodifiableMap(state);
    }

    private static <T> void describeCombo(Map<String, Object> state, ComboBox<T> combo) {
        state.put("type", "dropdown");
        state.put("value", display(combo, combo.getValue()));
        state.put("choices", combo.getItems().stream().map(item -> display(combo, item)).toList());
        state.put("index", combo.getSelectionModel().getSelectedIndex());
    }

    private static String color(Color value) {
        return String.format(Locale.ROOT, "#%02X%02X%02X%02X", Math.round(value.getRed() * 255),
                Math.round(value.getGreen() * 255), Math.round(value.getBlue() * 255),
                Math.round(value.getOpacity() * 255));
    }

    public void attachUrlMenus(Supplier<String> baseUrl, Consumer<String> copiedStatus) {
        Objects.requireNonNull(baseUrl);
        Objects.requireNonNull(copiedStatus);
        this.menuBaseUrl = baseUrl;
        this.menuCopiedStatus = copiedStatus;
        for (Entry entry : entries.values()) {
            Set<Node> targets = Collections.newSetFromMap(new IdentityHashMap<>());
            targets.add(entry.control());
            entry.targets().forEach(node -> collectTargets(node, targets));
            for (Node target : targets) {
                if (!menuTargets.add(target)) {
                    continue;
                }
                ContextMenu menu = target instanceof Control control ? control.getContextMenu() : null;
                boolean existing = menu != null;
                if (menu == null) {
                    menu = new ContextMenu();
                }
                menus.put(target, menu);
                if (existing && !menu.getItems().isEmpty()) {
                    menu.getItems().add(new SeparatorMenuItem());
                }
                menu.getItems().add(copyItem("Copy API URL", entry, null, baseUrl, copiedStatus));
                if (entry.control() instanceof ComboBox<?>) {
                    menu.getItems().add(copyItem("Copy API index URL", entry, "index", baseUrl, copiedStatus));
                }
                if (entry.control() instanceof Slider || entry.control() instanceof Spinner<?>) {
                    menu.getItems().addAll(copyItem("Copy API increment URL", entry, "increment", baseUrl, copiedStatus),
                            copyItem("Copy API decrement URL", entry, "decrement", baseUrl, copiedStatus));
                }
                if (target instanceof Control control) {
                    control.setContextMenu(menu);
                } else {
                    ContextMenu popup = menu;
                    target.addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
                        popup.show(target, event.getScreenX(), event.getScreenY());
                        event.consume();
                    });
                }
            }
        }
    }

    public void attachKeyImageMenus(ControlKeyImages images, Consumer<String> openBrowser,
                                    Consumer<String> status) {
        this.keyImages = images;
        this.keyImageBrowser = openBrowser;
        this.keyImageStatus = status;
        for (Entry entry : entries.values()) {
            Set<Node> targets = Collections.newSetFromMap(new IdentityHashMap<>());
            targets.add(entry.control());
            entry.targets().forEach(node -> collectTargets(node, targets));
            for (Node target : targets) {
                if (!imageMenuTargets.add(target)) {
                    continue;
                }
                ContextMenu menu = menus.get(target);
                if (menu == null) {
                    throw new IllegalStateException("Attach control URL menus before key image menus.");
                }
                menu.getItems().add(new SeparatorMenuItem());
                menu.getItems().add(imageItem("Open key image", entry, null, images, openBrowser, status));
                if (entry.control() instanceof Slider || entry.control() instanceof Spinner<?>) {
                    menu.getItems().addAll(
                            imageItem("Open increment key image", entry, "increment", images, openBrowser, status),
                            imageItem("Open decrement key image", entry, "decrement", images, openBrowser, status));
                }
            }
        }
    }

    private MenuItem imageItem(String label, Entry entry, String operation, ControlKeyImages images,
                               Consumer<String> openBrowser, Consumer<String> status) {
        MenuItem item = new MenuItem(label);
        item.setOnAction(event -> {
            List<Node> sources = new ArrayList<>();
            sources.add(entry.control());
            sources.addAll(entry.targets());
            try {
                openBrowser.accept(images.create(entry.id(), sources, operation).toUri().toString());
            } catch (java.io.IOException | RuntimeException ex) {
                status.accept("Could not open key image: " + ex.getMessage());
            }
        });
        return item;
    }

    private static void collectTargets(Node node, Set<Node> targets) {
        targets.add(node);
        if (node instanceof Parent parent && !(node instanceof Control)) {
            parent.getChildrenUnmodifiable().forEach(child -> collectTargets(child, targets));
        }
    }

    private MenuItem copyItem(String label, Entry entry, String operation, Supplier<String> baseUrl,
                               Consumer<String> copiedStatus) {
        MenuItem item = new MenuItem(label);
        item.setOnAction(event -> {
            String base = baseUrl.get();
            if (base == null || base.isBlank()) {
                copiedStatus.accept("Local DM API is not running.");
                return;
            }
            Map<String, String> query = Map.of();
            Map<String, Object> state = describe(entry);
            if ("index".equals(operation)) {
                int index = ((Number) state.get("index")).intValue();
                if (index < 0) {
                    copiedStatus.accept("Select a dropdown option before copying its index URL.");
                    return;
                }
                query = Map.of("index", Integer.toString(index));
            } else if (operation != null) {
                query = Map.of(operation, Objects.toString(state.getOrDefault("step", 1)));
            } else if (!(entry.control() instanceof ButtonBase)) {
                query = Map.of("value", Objects.toString(state.get("value"), ""));
            }
            String url = LocalApiServer.url(base, path(entry.id()), query);
            ClipboardContent content = new ClipboardContent();
            content.putString(url);
            if (Clipboard.getSystemClipboard().setContent(content)) {
                copiedStatus.accept("Copied API URL: " + url);
            } else {
                copiedStatus.accept("Could not copy API URL.");
            }
        });
        return item;
    }

    private static String path(String id) {
        return "/api/controls/" + id.replace('.', '/');
    }

    private static LocalApiServer.ApiException error(int status, String message) {
        return new LocalApiServer.ApiException(status, message);
    }

    private record Entry(String id, Node control, List<Node> targets) {
    }
}
