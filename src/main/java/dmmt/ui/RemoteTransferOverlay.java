package dmmt.ui;

import dmmt.remote.RemoteAssetTransfer;
import dmmt.remote.RemoteSessionClient;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Temporary, mouse-transparent card showing remote asset transfers; hidden when nothing is transferring. */
public final class RemoteTransferOverlay extends StackPane {
    private final Label title = new Label();
    private final Label detail = new Label();
    private final ProgressBar bar = new ProgressBar();
    private static final dmmt.log.Log LOG = dmmt.log.Log.of(RemoteTransferOverlay.class);

    public RemoteTransferOverlay() {
        title.getStyleClass().add("dialog-title");
        detail.getStyleClass().add("muted");
        bar.setMaxWidth(Double.MAX_VALUE);
        var card = new VBox(6, title, bar, detail);
        card.getStyleClass().add("remote-transfer");
        card.setMaxSize(VBox.USE_PREF_SIZE, VBox.USE_PREF_SIZE);
        getChildren().add(card);
        setAlignment(Pos.BOTTOM_CENTER);
        setPadding(new javafx.geometry.Insets(0, 0, 14, 0));
        setPickOnBounds(false);
        setMouseTransparent(true);
        setId("remoteTransferOverlay");
        setVisible(false);
    }

    /** Call on the JavaFX thread with the live transfers; an empty list hides the overlay. */
    public void update(List<RemoteSessionClient.TransferProgress> transfers) {
        update(transfers, sha -> null);
    }

    /** As {@link #update(List)}; {@code labels} maps an asset hash to a display name (or null when unknown). */
    public void update(List<RemoteSessionClient.TransferProgress> transfers, Function<String, String> labels) {
        if (transfers == null || transfers.isEmpty()) {
            if (isVisible()) {
                setVisible(false);
                LOG.info("Remote asset transfers finished");
            }
            return;
        }
        long total = 0;
        long done = 0;
        boolean upload = false;
        boolean download = false;
        var names = new LinkedHashSet<String>();
        for (var transfer : transfers) {
            total += transfer.totalBytes();
            done += transfer.transferredBytes();
            if (transfer.direction() == RemoteAssetTransfer.Direction.UPLOAD) upload = true;
            else download = true;
            String name = labels.apply(transfer.sha256());
            names.add(name == null || name.isBlank() ? "file" : name);
        }
        String verb = upload && download ? "Transferring" : upload ? "Uploading" : "Downloading";
        String what = names.size() == 1 ? names.iterator().next() : transfers.size() + " files";
        title.setText(verb + ": " + what);
        String size = "";
        if (total > 0) {
            bar.setProgress(Math.min(1.0, done / (double) total));
            size = String.format(Locale.ROOT, "%.1f / %.1f MB", done / 1_048_576.0, total / 1_048_576.0);
        } else {
            bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        }
        detail.setText(names.size() > 1 ? String.join(", ", names) + (size.isEmpty() ? "" : "  -  " + size) : size);
        if (!isVisible()) {
            LOG.info("{} ({} transfer{})", title.getText(), transfers.size(), transfers.size() == 1 ? "" : "s");
        }
        setVisible(true);
    }
}
