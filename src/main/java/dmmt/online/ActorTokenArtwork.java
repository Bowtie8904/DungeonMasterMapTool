package dmmt.online;

import dmmt.model.DmProject;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

public final class ActorTokenArtwork {
    private ActorTokenArtwork() {}

    public static void validate(Campaign.ActorArtwork art) throws IOException {
        if (art.getColor() == null || !art.getColor().matches("#[0-9a-fA-F]{6}")
                || !Double.isFinite(art.getSizeTiles()) || art.getSizeTiles() < .5
                || art.getSizeTiles() % .5 != 0
                || !Double.isFinite(art.getZoom()) || art.getZoom() < 1 || art.getZoom() > 10
                || !Double.isFinite(art.getCenterX()) || art.getCenterX() < 0 || art.getCenterX() > 1
                || !Double.isFinite(art.getCenterY()) || art.getCenterY() < 0 || art.getCenterY() > 1) {
            throw new IOException("Choose a border color, a positive size in half tiles, and a valid crop.");
        }
    }

    public static byte[] render(Path source, Campaign.ActorArtwork art) throws IOException {
        validate(art);
        BufferedImage image = ImageIO.read(source.toFile());
        if (image == null) throw new IOException("Cannot read the character image.");
        int edge = 256;
        double borderWidth = Math.max(2, edge * .055);
        double inset = borderWidth / 2;
        BufferedImage out = new BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            var circle = new Ellipse2D.Double(inset, inset, edge - borderWidth, edge - borderWidth);
            g.setClip(circle);
            Color color = Color.decode(art.getColor());
            g.setColor(color.darker().darker());
            g.fillRect(0, 0, edge, edge);
            double side = Math.min(image.getWidth(), image.getHeight()) / art.getZoom();
            double scale = edge / side;
            var transform = new java.awt.geom.AffineTransform();
            transform.translate(edge / 2.0 - art.getCenterX() * image.getWidth() * scale,
                    edge / 2.0 - art.getCenterY() * image.getHeight() * scale);
            transform.scale(scale, scale);
            g.drawImage(image, transform, null);
            g.setClip(null);
            g.setColor(color);
            g.setStroke(new BasicStroke((float) borderWidth));
            g.draw(circle);
        } finally {
            g.dispose();
        }
        var png = new ByteArrayOutputStream();
        if (!ImageIO.write(out, "png", png)) throw new IOException("Cannot encode the character token.");
        return png.toByteArray();
    }

    /** Baked actor artwork includes its mandatory border; do not draw a second one. */
    public static boolean synchronize(DmProject project, Campaign campaign, CampaignStore store) {
        boolean changed = false;
        var resized = new java.util.HashSet<String>();
        for (var token : project.getTokens()) {
            var actor = OnlineStateBuilder.actorOf(token, campaign);
            if (actor == null || actor.getArtwork() == null || actor.getTokenArtwork() == null) continue;
            var definition = project.getTokenDefinitions().get(token.getDefinitionId());
            if (definition == null || !matchesArtwork(definition.getImage(), actor.getArtwork())
                    || definition.getSizeTiles() != actor.getTokenArtwork().getSizeTiles())
                resized.add(token.getDefinitionId());
        }
        for (var token : project.getTokens()) {
            var actor = OnlineStateBuilder.actorOf(token, campaign);
            if (actor == null || actor.getArtwork() == null) continue;
            String image = store.artworkPath(campaign.getId(), actor.getArtwork()).toUri().toString();
            var definition = project.getTokenDefinitions().get(token.getDefinitionId());
            var art = actor.getTokenArtwork();
            double size = art == null ? 1 : art.getSizeTiles();
            boolean updateSize = resized.contains(token.getDefinitionId());
            if (definition == null) {
                definition = DmProject.TokenDefinition.builder().id(token.getDefinitionId()).name(actor.getName()).build();
                project.getTokenDefinitions().put(token.getDefinitionId(), definition);
            }
            if (updateSize || !matchesArtwork(definition.getImage(), actor.getArtwork())) {
                changed = true;
                definition.setImage(image);
            }
            definition.setName(actor.getName());
            if (art != null) {
                definition.setShape(DmProject.TokenShape.CIRCLE);
                definition.setBorder(false);
                definition.setColor(art.getColor());
                definition.setSizeTiles(size);
                if (updateSize) token.setSizeTiles(size);
            }
        }
        return changed;
    }

    private static boolean matchesArtwork(String reference, String hash) {
        return reference != null && reference.endsWith("/" + hash + ".png");
    }
}
