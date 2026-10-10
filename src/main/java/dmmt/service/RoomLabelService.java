package dmmt.service;

import dmmt.model.DmProject;
import dmmt.model.FogMask;
import dmmt.render.TextLayout;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Import-time label creation, independent of JavaFX and the DM editor. */
public final class RoomLabelService {
    private RoomLabelService() {
    }

    public static void addImportedRoomLabels(DmProject project) {
        FogMask mask = project.getFog().getMask();
        if (mask == null) {
            return;
        }
        var barrier = RoomFillService.buildBarrier(mask, project.getWalls(), project.getInteractables());
        List<double[]> centers = RoomFillService.enclosedRoomCenters(mask, barrier,
                project.getMap().getGrid().getPixelsPerCell());
        int fontSize = Tuning.ROOM_LABEL_FONT_SIZE.get();
        String textColor = Tuning.ROOM_LABEL_TEXT_COLOR.get();
        String background = Tuning.ROOM_LABEL_BACKGROUND_COLOR.get();
        String border = Tuning.ROOM_LABEL_BORDER_COLOR.get();
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
        FontRenderContext context = new FontRenderContext(null, true, true);
        TextLayout.Metrics metrics = new TextLayout.Metrics() {
            public double width(String text, int size) {
                return font.getStringBounds(text, context).getWidth();
            }

            public double lineHeight(int size) {
                return font.getLineMetrics("Ag", context).getHeight();
            }

            public double ascent(int size) {
                return font.getLineMetrics("Ag", context).getAscent();
            }
        };
        int number = 1;
        for (double[] center : centers) {
            DmProject.TextBox box = DmProject.TextBox.builder()
                    .id("text-" + UUID.randomUUID()).roomLabel(true).playerVisible(false).autoSize(true)
                    .roomLabelAnchored(true).roomLabelCenterX(center[0]).roomLabelCenterY(center[1])
                    .backgroundColor(background).borderColor(border)
                    .runs(List.of(DmProject.TextRun.builder().text(String.format(Locale.ROOT, "Room %02d", number++))
                            .fontSize(fontSize).color(textColor).build())).build();
            TextLayout.fitBox(box, Tuning.TEXT_AUTO_MAX_CELLS.get() * project.getMap().getGrid().getPixelsPerCell(),
                    fontSize, 12, metrics);
            project.getTextBoxes().add(box);
        }
    }
}
