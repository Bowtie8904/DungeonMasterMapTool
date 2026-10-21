package dmmt.ui;

import dmmt.model.DmProject.TokenDefinition;
import dmmt.model.DmProject.TokenShape;
import dmmt.render.TokenArt;
import dmmt.service.TokenLibraryService;
import javafx.event.ActionEvent;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

public final class TokenEditor
{
    private final TokenDefinition token;
    private final Canvas preview = new Canvas(280, 280);
    private final Slider zoom = new Slider(1, 10, 1);
    private final Label error = new Label();
    private Image source;
    private double lastX, lastY;

    private TokenEditor(TokenDefinition original)
    {
        token = TokenLibraryService.copy(original);
        if (token.getSourceImage() != null)
            source = TokenArt.decode(token.getSourceImage());
        zoom.setValue(token.getZoom());
    }

    public static Optional<TokenDefinition> show(Window owner, TokenDefinition original, Image initialImage)
    {
        return create(owner, original, initialImage).showAndWait();
    }

    static Dialog<TokenDefinition> create(Window owner, TokenDefinition original, Image initialImage)
    {
        TokenEditor editor = new TokenEditor(original);
        if (initialImage != null)
            editor.replace(initialImage);
        return editor.dialog(owner);
    }

    public static Image chooseImage(Window owner) throws IOException
    {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Token image");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp", "*.tif", "*.tiff", "*.wbmp"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showOpenDialog(owner);
        if (file == null)
            return null;
        return readImage(file);
    }

    static Image readImage(File file) throws IOException
    {
        Image image = new Image(file.toURI().toString());
        if (!image.isError() && image.getWidth() > 0)
            return image;
        var decoded = javax.imageio.ImageIO.read(file);
        if (decoded == null)
            throw new IOException("Cannot read this image format.", image.getException());
        var converted = new javafx.scene.image.WritableImage(decoded.getWidth(), decoded.getHeight());
        int[] pixels = decoded.getRGB(0, 0, decoded.getWidth(), decoded.getHeight(), null, 0, decoded.getWidth());
        converted.getPixelWriter().setPixels(0, 0, decoded.getWidth(), decoded.getHeight(),
                                             javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, decoded.getWidth());
        return converted;
    }

    private Dialog<TokenDefinition> dialog(Window owner)
    {
        Dialog<TokenDefinition> dialog = new Dialog<>();
        Dialogs.style(dialog, owner, "Token", "Create or edit a character token",
                      MaterialDesignA.ACCOUNT_CIRCLE_OUTLINE, false);
        TextField name = new TextField(token.getName());
        name.setId("token-name");
        name.setPromptText("Token name");
        ColorPicker color = new ColorPicker(Color.web(token.getColor()));
        color.setId("token-color");
        Spinner<Double> size = new Spinner<>(0.5, Double.MAX_VALUE, token.getSizeTiles(), 0.5);
        size.setId("token-size");
        size.setPrefWidth(110);
        CheckBox border = new CheckBox("Border");
        border.setId("token-border");
        border.setSelected(token.isBorder());
        border.selectedProperty().addListener((obs, was, value) -> {
            token.setBorder(value);
            redraw();
        });
        ComboBox<TokenShape> shape = new ComboBox<>();
        shape.setId("token-shape");
        shape.getItems().setAll(TokenShape.values());
        shape.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(TokenShape value) {
                return value == TokenShape.SQUARE ? "Square" : "Circle";
            }
            @Override public TokenShape fromString(String value) {
                return TokenShape.valueOf(value.toUpperCase(Locale.ROOT));
            }
        });
        shape.setValue(token.getShape());
        shape.valueProperty().addListener((obs, was, value) -> {
            token.setShape(value);
            redraw();
        });
        color.setOnAction(e -> {
            Color value = color.getValue();
            token.setColor(String.format(Locale.ROOT, "#%02X%02X%02X", Math.round(value.getRed() * 255),
                                         Math.round(value.getGreen() * 255), Math.round(value.getBlue() * 255)));
            redraw();
        });
        Button file = new Button("Choose image...");
        file.setOnAction(e -> attempt(() -> {
            Image image = chooseImage(owner);
            if (image != null)
                replace(image);
        }));
        Button paste = new Button("Paste image");
        paste.setOnAction(e -> attempt(() -> {
            Image image = Clipboard.getSystemClipboard().getImage();
            if (image == null)
                throw new IOException("The clipboard does not contain an image.");
            replace(image);
        }));
        Button remove = new Button("No image");
        remove.setOnAction(e -> {
            source = null;
            token.setSourceImage(null);
            token.setImage(null);
            redraw();
        });
        zoom.setId("token-zoom");
        zoom.valueProperty().addListener((obs, was, value) -> {
            token.setZoom(value.doubleValue());
            redraw();
        });
        preview.setOnMousePressed(e -> {
            preview.requestFocus();
            lastX = e.getX();
            lastY = e.getY();
        });
        preview.setOnMouseDragged(e -> {
            if (source == null)
                return;
            double factor = TokenArt.cropSide(source, token.getZoom()) / preview.getWidth();
            token.setCenterX(token.getCenterX() - (e.getX() - lastX) * factor / source.getWidth());
            token.setCenterY(token.getCenterY() - (e.getY() - lastY) * factor / source.getHeight());
            lastX = e.getX();
            lastY = e.getY();
            redraw();
        });
        preview.setOnScroll(e -> {
            zoom.setValue(zoom.getValue() * (e.getDeltaY() > 0 ? 1.1 : 1 / 1.1));
            e.consume();
        });
        Label hint = new Label("Drag to pan; use the slider or wheel to zoom.\nChanges update all maps using this token.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        error.getStyleClass().add("error-label");
        error.setWrapText(true);
        VBox content = new VBox(10, new Label("Name"), name,
                                new HBox(8, new Label("Color"), color, new Label("Tiles"), size),
                                new HBox(8, new Label("Shape"), shape, border),
                                preview, zoom, new HBox(8, file, paste, remove), hint, error);
        preview.setId("token-preview");
        dialog.getDialogPane().setContent(content);
        preview.setFocusTraversable(true);
        dialog.getDialogPane().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.V
                    && (!(e.getTarget() instanceof TextInputControl) || !Clipboard.getSystemClipboard().hasString())
                    && Clipboard.getSystemClipboard().hasImage())
            {
                paste.fire();
                e.consume();
            }
        });
        ButtonType save = new ButtonType("Save token", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, save);
        dialog.getDialogPane().lookupButton(save).addEventFilter(ActionEvent.ACTION, e -> {
            try
            {
                token.setName(name.getText());
                token.setSizeTiles(size.getValue());
                TokenLibraryService.validate(token);
                if (source != null)
                {
                    Canvas crop = new Canvas(256, 256);
                    TokenArt.drawCrop(crop.getGraphicsContext2D(), source, token, 256);
                    SnapshotParameters parameters = new SnapshotParameters();
                    parameters.setFill(Color.TRANSPARENT);
                    token.setImage(TokenArt.storeImage(crop.snapshot(parameters, null)));
                }
            }
            catch (IOException | IllegalArgumentException ex)
            {
                error.setText(ex.getMessage());
                e.consume();
            }
        });
        dialog.setResultConverter(button -> button == save ? token : null);
        redraw();
        return dialog;
    }

    private void replace(Image image)
    {
        try
        {
            String data = TokenArt.storeImage(image);
            source = image;
            token.setSourceImage(data);
            token.setImage(null);
            token.setCenterX(.5);
            token.setCenterY(.5);
            zoom.setValue(1);
            token.setZoom(1);
            redraw();
        }
        catch (IOException ex)
        {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    private void redraw()
    {
        var gc = preview.getGraphicsContext2D();
        gc.clearRect(0, 0, 280, 280);
        zoom.setDisable(source == null);
        if (source == null)
        {
            TokenArt.draw(gc, token, 140, 140, 274);
            return;
        }
        TokenArt.constrain(token, source);
        gc.save();
        gc.beginPath();
        if (token.getShape() == TokenShape.SQUARE)
            gc.rect(3, 3, 274, 274);
        else
            gc.arc(140, 140, 137, 137, 0, 360);
        gc.closePath();
        gc.clip();
        if (token.isBorder())
        {
            gc.setFill(Color.web(token.getColor()).darker().darker());
            gc.fillRect(0, 0, 280, 280);
        }
        TokenArt.drawCrop(gc, source, token, 280);
        gc.restore();
        if (!token.isBorder())
            return;
        gc.setStroke(Color.web(token.getColor()));
        gc.setLineWidth(6);
        if (token.getShape() == TokenShape.SQUARE)
            gc.strokeRect(3, 3, 274, 274);
        else
            gc.strokeOval(3, 3, 274, 274);
    }

    @FunctionalInterface
    private interface ImageAction
    {
        void run() throws IOException;
    }

    private void attempt(ImageAction action)
    {
        try
        {
            action.run();
            error.setText("");
        }
        catch (IOException | IllegalArgumentException ex)
        {
            error.setText(ex.getMessage());
        }
    }
}
