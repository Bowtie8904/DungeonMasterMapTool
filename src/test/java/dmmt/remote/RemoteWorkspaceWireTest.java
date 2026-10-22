package dmmt.remote;

import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RemoteWorkspaceWireTest {
    @Test
    void absentTargetsAndPresentJsonNullRemainDistinctAcrossWire() throws Exception {
        var changes = List.of(
                new RemoteWorkspaceAuthority.Change("map:imagePath", 0, TextNode.valueOf("old"), NullNode.instance),
                new RemoteWorkspaceAuthority.Change("overlays:deleted", 0, TextNode.valueOf("old"), null),
                new RemoteWorkspaceAuthority.Change("overlays:added", 0, null, TextNode.valueOf("new")));
        var request = new RemoteWorkspaceAuthority.Request(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, 0, "Change targets", changes);
        var encoded = RemoteWire.message(RemoteWire.Type.WORKSPACE_EDIT, request);
        var decoded = RemoteWire.payload(encoded, RemoteWorkspaceAuthority.Request.class);
        assertEquals(request, decoded);
        assertTrue(decoded.changes().getFirst().replacement().isNull());
        assertNull(decoded.changes().get(1).replacement());
        assertNull(decoded.changes().get(2).expected());
    }

    @Test
    void workspaceArtworkManifestsRoundTripAndMustMatchEveryPortableReference() throws Exception {
        var project = new DmProject();
        String reference = "asset:" + "a".repeat(64);
        project.getMap().setImagePath(reference);
        var document = RemoteProjectDocument.capture(project);
        var asset = new RemoteAssetStore.AssetDescriptor("a".repeat(64), 42);
        var state = new RemoteWorkspaceAuthority.State(UUID.randomUUID(), UUID.randomUUID(), 0, 1,
                document, java.util.Map.of(), java.util.Map.of(reference, asset));
        assertEquals(state, RemoteWire.payload(RemoteWire.message(RemoteWire.Type.WORKSPACE_STATE, state),
                RemoteWorkspaceAuthority.State.class));
        assertThrows(IllegalArgumentException.class, () -> new RemoteWorkspaceAuthority.State(
                state.sessionId(), state.contextId(), 0, 1, document, java.util.Map.of(), java.util.Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new RemoteWorkspaceAuthority.State(
                state.sessionId(), state.contextId(), 0, 1, document, java.util.Map.of(),
                java.util.Map.of(reference, new RemoteAssetStore.AssetDescriptor("b".repeat(64), 42))));
    }

    @Test
    void workspaceSnapshotAndConditionalQueryRoundTrip() throws Exception {
        DmProject project = DmProject.builder().build();
        var authority = new RemoteWorkspaceAuthority(() -> project, commit -> fail("Unexpected mutation"));
        var state = authority.state();
        assertEquals(state, RemoteWire.payload(RemoteWire.message(RemoteWire.Type.WORKSPACE_STATE, state),
                RemoteWorkspaceAuthority.State.class));
        var query = new RemoteProtocol.WorkspaceQuery(state);
        assertEquals(query, RemoteWire.payload(RemoteWire.message(RemoteWire.Type.WORKSPACE_STATE, query),
                RemoteProtocol.WorkspaceQuery.class));
        assertTrue(query.matches(state));
    }
}
