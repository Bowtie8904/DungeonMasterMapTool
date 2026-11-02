package dmmt.ui;

import dmmt.model.DmProject;
import dmmt.online.OnlineMeasurement;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.List;

/** DM-side display and picking for shared, non-persisted player measurements. */
public final class OnlineMeasurementOverlay {
    private OnlineMeasurementOverlay() {
    }

    public static OnlineMeasurement pickDelete(List<OnlineMeasurement> items, double x, double y, double zoom) {
        for (int i = items.size() - 1; i >= 0; i--) {
            OnlineMeasurement m = items.get(i);
            if (Math.abs(x - m.x2() - 12 / zoom) <= 9 / zoom
                    && Math.abs(y - m.y2() - 12 / zoom) <= 9 / zoom) return m;
        }
        return null;
    }

    public static OnlineMeasurement pick(List<OnlineMeasurement> items, double x, double y, double tolerance) {
        for (int i = items.size() - 1; i >= 0; i--) {
            OnlineMeasurement m = items.get(i);
            double dx = m.x2() - m.x1(), dy = m.y2() - m.y1();
            double radius = Math.hypot(dx, dy), distance = Math.hypot(x - m.x1(), y - m.y1());
            if ("rect".equals(m.kind()) && x >= Math.min(m.x1(), m.x2()) - tolerance
                    && x <= Math.max(m.x1(), m.x2()) + tolerance && y >= Math.min(m.y1(), m.y2()) - tolerance
                    && y <= Math.max(m.y1(), m.y2()) + tolerance) return m;
            if ("radius".equals(m.kind()) && distance <= radius + tolerance) return m;
            if ("cone".equals(m.kind())) {
                double angle = Math.atan2(y - m.y1(), x - m.x1()) - Math.atan2(dy, dx);
                if (distance <= radius + tolerance && Math.abs(Math.atan2(Math.sin(angle), Math.cos(angle))) <= Math.PI / 6) return m;
            }
            double t = Math.clamp(((x - m.x1()) * dx + (y - m.y1()) * dy) / Math.max(0.001, dx * dx + dy * dy), 0, 1);
            if (Math.hypot(x - m.x1() - t * dx, y - m.y1() - t * dy) <= tolerance
                    || Math.hypot(x - m.x2(), y - m.y2()) <= tolerance * 3) return m;
        }
        return null;
    }

    public static void draw(GraphicsContext gc, List<OnlineMeasurement> items, DmProject.CameraState camera,
                            double width, double height, double ppc, String selected) {
        gc.save();
        double z = camera.getZoom();
        gc.translate(width / 2 - camera.getX() * z, height / 2 - camera.getY() * z);
        gc.scale(z, z);
        gc.setLineWidth(2 / z);
        gc.setFont(Font.font(12 / z));
        for (OnlineMeasurement m : items) {
            gc.setStroke(m.id().equals(selected) ? Color.WHITE : Color.web("#80d8ff"));
            gc.setFill(Color.web("#80d8ff", 0.12));
            double r = Math.hypot(m.x2() - m.x1(), m.y2() - m.y1());
            gc.beginPath();
            switch (m.kind()) {
                case "radius" -> {
                    gc.arc(m.x1(), m.y1(), r, r, 0, 360);
                    gc.moveTo(m.x1(), m.y1()); gc.lineTo(m.x2(), m.y2());
                }
                case "rect" -> gc.rect(Math.min(m.x1(), m.x2()), Math.min(m.y1(), m.y2()),
                        Math.abs(m.x2() - m.x1()), Math.abs(m.y2() - m.y1()));
                case "cone" -> {
                    double angle = -Math.toDegrees(Math.atan2(m.y2() - m.y1(), m.x2() - m.x1()));
                    gc.moveTo(m.x1(), m.y1());
                    double start = Math.toRadians(angle - 30);
                    gc.lineTo(m.x1() + Math.cos(start) * r, m.y1() - Math.sin(start) * r);
                    gc.arc(m.x1(), m.y1(), r, r, angle - 30, 60);
                    gc.closePath();
                }
                default -> { gc.moveTo(m.x1(), m.y1()); gc.lineTo(m.x2(), m.y2()); }
            }
            if (!"distance".equals(m.kind())) gc.fill();
            gc.stroke();
            String label = m.label(ppc);
            javafx.scene.text.Text text = new javafx.scene.text.Text(label);
            text.setFont(gc.getFont());
            gc.setFill(Color.web("#151a20", 0.95));
            gc.fillRect(m.x2() - 5 / z, m.y2() - 22 / z, text.getLayoutBounds().getWidth() + 10 / z, 20 / z);
            gc.setFill(Color.WHITE);
            gc.fillText(label, m.x2(), m.y2() - 8 / z);
            double x = m.x2() + 12 / z, y = m.y2() + 12 / z, half = 8 / z;
            gc.setFill(Color.web("#151a20", 0.6));
            gc.fillRect(x - half, y - half, half * 2, half * 2);
            gc.setStroke(Color.web("#cbd5e1", 0.67));
            gc.setLineWidth(1.2 / z);
            gc.strokeLine(x - half / 3, y - half / 3, x + half / 3, y + half / 3);
            gc.strokeLine(x + half / 3, y - half / 3, x - half / 3, y + half / 3);
            gc.setLineWidth(2 / z);
        }
        gc.restore();
    }
}
