package dmmt.ui;

import dmmt.log.Log;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;

import java.util.function.Consumer;

/** Modal host-side prompt for an incoming remote control request. */
public final class RemoteApprovalDialog {
    private static final Log LOG = Log.of(RemoteApprovalDialog.class);
    public enum Decision { ALLOW_ONCE, ALWAYS_ALLOW, BLOCK, DENY }

    private final Dialog<ButtonType> dialog = new Dialog<>();

    /**
     * @param replacing name of the currently approved client that this request would replace, or {@code null}
     * @param decided   called once with the host's decision (closing the window counts as deny)
     */
    public RemoteApprovalDialog(Window owner, String clientName, String address, String replacing,
                                Consumer<Decision> decided) {
        dialog.initModality(Modality.APPLICATION_MODAL);
        Dialogs.style(dialog, owner, "Remote control request", "A remote client wants to control this workspace",
                MaterialDesignL.LAN_CONNECT, false);
        String text = "Unverified client name: " + clientName + " at " + address + ".\n\n"
                + "Allow only an expected request. Access grants full workspace/settings control and file transfer.";
        if (replacing != null) {
            text += "\n\nAllowing this replaces the current client (" + replacing
                    + "); its connection will be closed.";
        }
        Label content = new Label(text);
        content.setWrapText(true);
        content.setMaxWidth(520);
        dialog.getDialogPane().setContent(content);
        ButtonType once = new ButtonType("Allow once", ButtonBar.ButtonData.OK_DONE);
        ButtonType always = new ButtonType("Always allow", ButtonBar.ButtonData.OTHER);
        ButtonType block = new ButtonType("Block", ButtonBar.ButtonData.OTHER);
        ButtonType deny = new ButtonType("Deny", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(once, always, block, deny);
        LOG.info("Remote control request prompt shown for {}", address);
        dialog.setOnHidden(event -> {
            ButtonType result = dialog.getResult();
            Decision decision = result == once ? Decision.ALLOW_ONCE : result == always ? Decision.ALWAYS_ALLOW
                    : result == block ? Decision.BLOCK : Decision.DENY;
            LOG.info("Remote control request from {} decided: {}", address, decision);
            decided.accept(decision);
        });
    }

    public void show() {
        dialog.show();
    }

    /** Closes the prompt without a decision being applied, e.g. because the request expired. */
    public void dismiss() {
        dialog.setOnHidden(null);
        dialog.close();
    }
}
