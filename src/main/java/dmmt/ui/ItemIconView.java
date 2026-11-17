package dmmt.ui;

import dmmt.online.ItemArtwork;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Renders trusted bundled game-icons SVG geometry without a WebView or network requests. */
final class ItemIconView {
    private record Path(String data, boolean evenOdd) { }
    private record Geometry(List<Path> paths) { }
    private static final ConcurrentHashMap<String, Geometry> CACHE = new ConcurrentHashMap<>();

    private ItemIconView() { }

    static Node create(String image) {
        if (image == null || image.isBlank()) return null;
        try {
            Geometry geometry = CACHE.computeIfAbsent(image, ItemIconView::read);
            Group drawing = new Group(new Rectangle(512, 512, Color.TRANSPARENT));
            for (Path data : geometry.paths()) {
                SVGPath path = new SVGPath();
                path.setContent(data.data());
                path.setFill(Color.WHITE);
                path.setFillRule(data.evenOdd() ? FillRule.EVEN_ODD : FillRule.NON_ZERO);
                drawing.getChildren().add(path);
            }
            drawing.getTransforms().add(new Scale(28.0 / 512, 28.0 / 512));
            // Wrap so layout uses the scaled 28x28 bounds instead of the untransformed 512x512 bounds.
            StackPane icon = new StackPane(new Group(drawing));
            icon.setMinSize(28, 28);
            icon.setPrefSize(28, 28);
            icon.setMaxSize(28, 28);
            icon.setMouseTransparent(true);
            return icon;
        } catch (IllegalArgumentException ex) {
            Label error = new Label("!");
            error.setStyle("-fx-text-fill: #ff8a80;");
            Tooltip.install(error, new Tooltip("Item image unavailable: " + image + "\n" + ex.getMessage()));
            return error;
        }
    }

    private static Geometry read(String image) {
        var resource = ItemArtwork.resource(image);
        if (resource == null) throw new IllegalArgumentException("Expected an existing bundled item icon.");
        try (var stream = resource.openStream()) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Element svg = factory.newDocumentBuilder().parse(stream).getDocumentElement();
            if (!"0 0 512 512".equals(svg.getAttribute("viewBox")))
                throw new IllegalArgumentException("Unsupported item icon viewBox.");
            List<Path> paths = new ArrayList<>();
            var elements = svg.getElementsByTagName("path");
            for (int i = 0; i < elements.getLength(); i++) {
                Element path = (Element) elements.item(i);
                paths.add(new Path(path.getAttribute("d"), "evenodd".equals(path.getAttribute("fill-rule"))));
            }
            if (paths.isEmpty()) throw new IllegalArgumentException("Item icon contains no paths.");
            return new Geometry(List.copyOf(paths));
        } catch (IOException | SAXException | ParserConfigurationException ex) {
            throw new IllegalArgumentException("Could not read bundled item icon.", ex);
        }
    }
}
