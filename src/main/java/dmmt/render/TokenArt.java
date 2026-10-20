package dmmt.render;

import dmmt.model.DmProject.TokenDefinition;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TokenArt
{
    private static final Map<String, Image> IMAGES = new LinkedHashMap<>(16, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> entry)
        {
            return size() > 128;
        }
    };

    private TokenArt()
    {
    }

    public static Image decode(String data)
    {
        Image image = new Image(new ByteArrayInputStream(Base64.getDecoder().decode(data)));
        if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0)
        {
            throw new IllegalArgumentException("This image format cannot be read.", image.getException());
        }
        return image;
    }

    public static String encode(Image image) throws IOException
    {
        int width = (int)image.getWidth(), height = (int)image.getHeight();
        if (width <= 0 || height <= 0 || image.getPixelReader() == null)
            throw new IOException("The image has no pixels.");
        int[] pixels = new int[Math.multiplyExact(width, height)];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        BufferedImage png = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        png.setRGB(0, 0, width, height, pixels, 0, width);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(png, "png", out))
            throw new IOException("PNG encoding is unavailable.");
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    public static double cropSide(Image image, double zoom)
    {
        return Math.min(image.getWidth(), image.getHeight()) / zoom;
    }

    public static void constrain(TokenDefinition token, Image source)
    {
        token.setCenterX(Math.clamp(token.getCenterX(), 0, 1));
        token.setCenterY(Math.clamp(token.getCenterY(), 0, 1));
    }

    public static void drawCrop(GraphicsContext gc, Image image, TokenDefinition token, double size)
    {
        double side = cropSide(image, token.getZoom());
        double left = token.getCenterX() * image.getWidth() - side / 2;
        double top = token.getCenterY() * image.getHeight() - side / 2;
        double x = Math.max(0, left), y = Math.max(0, top);
        double width = Math.min(image.getWidth(), left + side) - x;
        double height = Math.min(image.getHeight(), top + side) - y;
        gc.drawImage(image, x, y, width, height, (x - left) * size / side,
                     (y - top) * size / side, width * size / side, height * size / side);
    }

    public static void draw(GraphicsContext gc, TokenDefinition token, double x, double y, double diameter)
    {
        double radius = diameter / 2;
        Color color = Color.web(token.getColor());
        gc.save();
        gc.beginPath();
        gc.arc(x, y, radius, radius, 0, 360);
        gc.closePath();
        gc.clip();
        gc.setFill(color.darker().darker());
        gc.fillOval(x - radius, y - radius, diameter, diameter);
        if (token.getImage() != null)
        {
            Image image = IMAGES.computeIfAbsent(token.getImage(), TokenArt::decode);
            gc.drawImage(image, x - radius, y - radius, diameter, diameter);
        }
        else
        {
            gc.setFill(color.brighter());
            gc.fillOval(x - diameter * .14, y - diameter * .30, diameter * .28, diameter * .28);
            gc.fillOval(x - diameter * .31, y + diameter * .03, diameter * .62, diameter * .55);
        }
        gc.restore();
        gc.save();
        gc.setLineDashes();
        gc.setStroke(color);
        gc.setLineWidth(Math.max(2, diameter * .055));
        gc.strokeOval(x - radius + 1, y - radius + 1, diameter - 2, diameter - 2);
        gc.restore();
    }
}
