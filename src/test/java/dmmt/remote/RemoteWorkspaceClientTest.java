package dmmt.remote;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class RemoteWorkspaceClientTest {
    @Test
    void acceptedEditsReconcileAndUnrelatedHostEditsDoNotOverwritePendingPreview() throws Exception {
        var host = DmProject.builder().build();
        host.getOverlays().add(DmProject.OverlayShape.builder().id("circle").build());
        var authority = new RemoteWorkspaceAuthority(() -> host,
                commit -> RemoteProjectChanges.apply(host, commit.after(), commit.targets()));
        var shown = new ArrayList<RemoteProjectDocument>();
        var notices = new ArrayList<RemoteWorkspaceClient.Notice>();
        var client = new RemoteWorkspaceClient(authority.state(), shown::add, notices::add);
        var edited = authority.state().document().materialize(host.getViews().getDmCamera());
        edited.getOverlays().getFirst().setX(44);
        var request = client.submit(RemoteProjectDocument.capture(edited), "Move circle");
        assertFalse(client.canEdit());
        host.getMap().setRotationQuarterTurns(2);
        authority.observeHostCommit();
        client.update(authority.state());
        var preview = shown.getLast().materialize(host.getViews().getDmCamera());
        assertEquals(44, preview.getOverlays().getFirst().getX());
        assertEquals(2, preview.getMap().getRotationQuarterTurns());
        assertTrue(client.receive(authority.execute(request)));
        assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, notices.getLast().outcome());
        assertTrue(client.canEdit());
        assertEquals(44, host.getOverlays().getFirst().getX());
    }

    @Test
    void hostConflictRollsBackPreviewAndExplainsRejection() throws Exception {
        var host = DmProject.builder().build();
        var authority = new RemoteWorkspaceAuthority(() -> host,
                commit -> RemoteProjectChanges.apply(host, commit.after(), commit.targets()));
        var shown = new ArrayList<RemoteProjectDocument>();
        var notices = new ArrayList<RemoteWorkspaceClient.Notice>();
        var client = new RemoteWorkspaceClient(authority.state(), shown::add, notices::add);
        var edited = authority.state().document().materialize(host.getViews().getDmCamera());
        edited.getMap().setRotationQuarterTurns(1);
        var request = client.submit(RemoteProjectDocument.capture(edited), "Rotate");
        host.getMap().setRotationQuarterTurns(2);
        authority.observeHostCommit();
        client.receive(authority.execute(request));
        assertEquals(RemoteWorkspaceAuthority.Status.CONFLICT, notices.getLast().outcome());
        assertEquals(2, shown.getLast().materialize(host.getViews().getDmCamera()).getMap().getRotationQuarterTurns());
    }

    @Test
    void lostAcknowledgementRecoversExactRequestWithoutReapplyingAndWithoutRollingBackNewerState() throws Exception {
        var host = DmProject.builder().build();
        var authority = new RemoteWorkspaceAuthority(() -> host,
                commit -> RemoteProjectChanges.apply(host, commit.after(), commit.targets()));
        var shown = new ArrayList<RemoteProjectDocument>();
        var client = new RemoteWorkspaceClient(authority.state(), shown::add, ignored -> {});
        var edited = authority.state().document().materialize(host.getViews().getDmCamera());
        edited.getMap().setRotationQuarterTurns(1);
        var request = client.submit(RemoteProjectDocument.capture(edited), "Rotate");
        var acknowledged = authority.execute(request);
        client.pause();
        assertFalse(client.canEdit());
        host.getMap().setRotationQuarterTurns(2);
        authority.observeHostCommit();
        assertTrue(client.resume(authority.state()));
        assertSame(request, client.request());
        assertTrue(client.receive(authority.execute(client.request())));
        assertEquals(acknowledged.state().nextSequence(), client.accepted().nextSequence());
        assertEquals(2, client.accepted().document().materialize(host.getViews().getDmCamera())
                .getMap().getRotationQuarterTurns());
    }

    @Test
    void hostRestartRequiresExplicitFreshSynchronization() {
        var host = DmProject.builder().build();
        var first = new RemoteWorkspaceAuthority(() -> host, ignored -> {});
        var client = new RemoteWorkspaceClient(first.state(), ignored -> {}, ignored -> {});
        client.pause();
        var restarted = new RemoteWorkspaceAuthority(() -> host, ignored -> {});
        assertFalse(client.resume(restarted.state()));
        assertEquals(RemoteWorkspaceClient.Phase.RESYNC_REQUIRED, client.phase());
    }
}
