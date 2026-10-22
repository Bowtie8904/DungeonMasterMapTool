package dmmt.remote;

import dmmt.model.DmProject;
import dmmt.model.FogMask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteWorkspaceAuthorityTest {
    @Test
    void completedShapeAndTokenMoveCommitTogetherWithoutChangingIndependentDmCamera() throws Exception {
        var project = new AtomicReference<>(project());
        List<RemoteWorkspaceAuthority.Commit> history = new ArrayList<>();
        try (var authority = authority(project, history)) {
            var baseline = authority.state();
            var client = baseline.document().materialize(camera(700, 800));
            client.getTokens().getFirst().setX(120);
            client.getOverlays().add(DmProject.OverlayShape.builder().id("shape-1").type("circle").radius(80).build());
            var request = RemoteWorkspaceAuthority.diff(baseline, RemoteProjectDocument.capture(client), "Move and draw");
            var result = authority.execute(request);
            assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, result.status());
            assertEquals(120, project.get().getTokens().getFirst().getX());
            assertEquals(1, project.get().getOverlays().size());
            assertEquals(10, project.get().getViews().getDmCamera().getX());
            assertEquals(20, project.get().getViews().getDmCamera().getY());
            assertEquals(1, history.size());
            assertEquals(RemoteWorkspaceAuthority.Origin.REMOTE, history.getFirst().origin());
            assertEquals("Move and draw", history.getFirst().label());
            assertEquals(Set.of("tokens:token-1", "overlays:shape-1"), history.getFirst().targets());
            assertSame(result, authority.execute(request));
            assertEquals(1, history.size());
        }
    }

    @Test
    void unrelatedHostChangesMergeButChangesAwayAndBackConflict() throws Exception {
        var project = new AtomicReference<>(project());
        try (var authority = authority(project, new ArrayList<>())) {
            var baseline = authority.state();
            var client = baseline.document().materialize(camera(0, 0));
            client.getTokens().getFirst().setX(150);
            var request = RemoteWorkspaceAuthority.diff(baseline, RemoteProjectDocument.capture(client), "Move token");
            project.get().getWeather().setType("rain");
            authority.observeHostCommit();
            assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, authority.execute(request).status());
            assertEquals("rain", project.get().getWeather().getType());
            var next = authority.state();
            client = next.document().materialize(camera(0, 0));
            client.getTokens().getFirst().setX(170);
            var stale = RemoteWorkspaceAuthority.diff(next, RemoteProjectDocument.capture(client), "Stale move");
            project.get().getTokens().getFirst().setX(160);
            authority.observeHostCommit();
            project.get().getTokens().getFirst().setX(150);
            authority.observeHostCommit();
            assertEquals(RemoteWorkspaceAuthority.Status.CONFLICT, authority.execute(stale).status());
            assertEquals(150, project.get().getTokens().getFirst().getX());
        }
    }

    @Test
    void unrelatedAdditionsAndDeletionsRetainStableTargetsAndDoNotResurrectHostDeletions() throws Exception {
        var project = new AtomicReference<>(project());
        try (var authority = authority(project, new ArrayList<>())) {
            var baseline = authority.state();
            var client = baseline.document().materialize(camera(0, 0));
            client.getOverlays().add(DmProject.OverlayShape.builder().id("remote-shape").type("rect").build());
            project.get().getOverlays().add(DmProject.OverlayShape.builder().id("host-shape").type("circle").build());
            authority.observeHostCommit();
            assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, authority.execute(
                    RemoteWorkspaceAuthority.diff(baseline, RemoteProjectDocument.capture(client), "Add shape")).status());
            assertEquals(2, project.get().getOverlays().size());
            var next = authority.state();
            client = next.document().materialize(camera(0, 0));
            client.getTokens().getFirst().setY(99);
            var stale = RemoteWorkspaceAuthority.diff(next, RemoteProjectDocument.capture(client), "Move deleted token");
            project.get().getTokens().clear();
            authority.observeHostCommit();
            assertEquals(RemoteWorkspaceAuthority.Status.CONFLICT, authority.execute(stale).status());
            assertTrue(project.get().getTokens().isEmpty());
        }
    }

    @Test
    void hostGestureLocksRejectOverlapAndPreserveUnrelatedUnfinishedHostPreview() throws Exception {
        var project = new AtomicReference<>(project());
        try (var authority = authority(project, new ArrayList<>())) {
            var baseline = authority.state();
            project.get().getTokens().getFirst().setX(999); // Host's live uncommitted drag.
            authority.protectHostTargets(Set.of("tokens:token-1"));
            var client = baseline.document().materialize(camera(0, 0));
            client.getWeather().setType("snow");
            assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, authority.execute(
                    RemoteWorkspaceAuthority.diff(baseline, RemoteProjectDocument.capture(client), "Set snow")).status());
            assertEquals(999, project.get().getTokens().getFirst().getX());
            assertEquals(0, authority.state().document().materialize(camera(0, 0)).getTokens().getFirst().getX());
            var next = authority.state();
            client = next.document().materialize(camera(0, 0));
            client.getTokens().getFirst().setX(123);
            assertEquals(RemoteWorkspaceAuthority.Status.BUSY, authority.execute(
                    RemoteWorkspaceAuthority.diff(next, RemoteProjectDocument.capture(client), "Overlapping move")).status());
            assertEquals(999, project.get().getTokens().getFirst().getX());
            authority.protectHostTargets(Set.of());
            authority.observeHostCommit();
            assertEquals(999, authority.state().document().materialize(camera(0, 0)).getTokens().getFirst().getX());
        }
    }

    @Test
    void returningToSameMapStillInvalidatesDialogsAndGesturesFromItsPreviousContext() throws Exception {
        var project = new AtomicReference<>(project());
        try (var authority = authority(project, new ArrayList<>())) {
            var baseline = authority.state();
            var client = baseline.document().materialize(camera(0, 0));
            client.getWeather().setType("rain");
            var stale = RemoteWorkspaceAuthority.diff(baseline, RemoteProjectDocument.capture(client), "Old dialog");
            var oldProject = project.get();
            project.set(project());
            authority.switchContext();
            project.set(oldProject);
            authority.switchContext();
            assertNotEquals(baseline.contextId(), authority.state().contextId());
            assertEquals(RemoteWorkspaceAuthority.Status.CONFLICT, authority.execute(stale).status());
            assertEquals("none", project.get().getWeather().getType());
        }
    }

    @Test
    void compoundValidationDoesNotApplyPartialChangesOrEnterHistory() throws Exception {
        var project = new AtomicReference<>(project());
        List<RemoteWorkspaceAuthority.Commit> history = new ArrayList<>();
        try (var authority = authority(project, history)) {
            var baseline = authority.state();
            var client = baseline.document().materialize(camera(0, 0));
            client.getWeather().setType("rain");
            client.getTokens().getFirst().setDefinitionId("unavailable");
            var result = authority.execute(RemoteWorkspaceAuthority.diff(
                    baseline, RemoteProjectDocument.capture(client), "Invalid compound"));
            assertEquals(RemoteWorkspaceAuthority.Status.INVALID, result.status());
            assertEquals("none", project.get().getWeather().getType());
            assertEquals("definition-1", project.get().getTokens().getFirst().getDefinitionId());
            assertTrue(history.isEmpty());
        }
    }

    @Test
    void independentFogBlocksMergeWhileGeometryChangesInvalidateOldFogCommands() throws Exception {
        var project = new AtomicReference<>(project());
        project.get().getFog().setMask(new FogMask(0, 0, 10, 200, 100));
        try (var authority = authority(project, new ArrayList<>())) {
            var baseline = authority.state();
            var client = baseline.document().materialize(camera(0, 0));
            client.getFog().getMask().applyCells(new int[]{1}, true);
            var request = RemoteWorkspaceAuthority.diff(baseline, RemoteProjectDocument.capture(client), "Reveal cells");
            project.get().getFog().getMask().applyCells(new int[]{9000}, true);
            authority.observeHostCommit();
            assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, authority.execute(request).status());
            assertTrue(project.get().getFog().getMask().isRevealedCell(1));
            assertTrue(project.get().getFog().getMask().isRevealedCell(9000));
            var next = authority.state();
            client = next.document().materialize(camera(0, 0));
            client.getFog().getMask().applyCells(new int[]{2}, true);
            var stale = RemoteWorkspaceAuthority.diff(next, RemoteProjectDocument.capture(client), "Old fog");
            project.get().getFog().getMask().resizeToCover(-200, -200, 2500, 1500);
            authority.observeHostCommit();
            assertEquals(RemoteWorkspaceAuthority.Status.CONFLICT, authority.execute(stale).status());
        }
    }

    @Test
    void receiptsCannotBeReusedWithDifferentCommandsOrBlindlyReplayedAfterEviction() throws Exception {
        var project = new AtomicReference<>(project());
        try (var authority = authority(project, new ArrayList<>())) {
            var firstState = authority.state();
            var client = firstState.document().materialize(camera(0, 0));
            client.getTokens().getFirst().setX(1);
            var first = RemoteWorkspaceAuthority.diff(firstState, RemoteProjectDocument.capture(client), "Move");
            assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, authority.execute(first).status());
            var altered = new RemoteWorkspaceAuthority.Request(first.sessionId(), first.contextId(), UUID.randomUUID(),
                    first.sequence(), first.baseRevision(), first.label(), first.changes());
            assertEquals(RemoteWorkspaceAuthority.Status.RESYNC_REQUIRED, authority.execute(altered).status());
            for (int i = 2; i <= 258; i++) {
                var state = authority.state();
                client = state.document().materialize(camera(0, 0));
                client.getTokens().getFirst().setX(i);
                assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, authority.execute(
                        RemoteWorkspaceAuthority.diff(state, RemoteProjectDocument.capture(client), "Move")).status());
            }
            assertEquals(RemoteWorkspaceAuthority.Status.RESYNC_REQUIRED, authority.execute(first).status());
            assertEquals(258, project.get().getTokens().getFirst().getX());
        }
    }

    @Test
    void applicationOwnerBoundaryIsEnforced() {
        var project = new AtomicReference<>(project());
        try (var authority = authority(project, new ArrayList<>())) {
            CompletableFuture.runAsync(() -> {
                assertThrows(IllegalStateException.class, authority::state);
                assertThrows(IllegalStateException.class, authority::observeHostCommit);
                assertThrows(IllegalStateException.class, authority::switchContext);
                assertThrows(IllegalStateException.class, authority::close);
            }).join();
        }
    }

    private static RemoteWorkspaceAuthority authority(AtomicReference<DmProject> project,
                                                      List<RemoteWorkspaceAuthority.Commit> history) {
        return new RemoteWorkspaceAuthority(project::get, commit -> {
            history.add(commit);
            project.set(commit.after());
        });
    }

    private static DmProject project() {
        var project = DmProject.builder().build();
        project.getViews().setDmCamera(camera(10, 20));
        project.getTokenDefinitions().put("definition-1",
                DmProject.TokenDefinition.builder().id("definition-1").build());
        project.getTokens().add(DmProject.TokenInstance.builder().id("token-1").definitionId("definition-1").build());
        return project;
    }

    private static DmProject.CameraState camera(double x, double y) {
        return DmProject.CameraState.builder().x(x).y(y).zoom(1).build();
    }
}
