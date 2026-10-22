package dmmt.remote;

import dmmt.service.AppSettings;
import dmmt.service.HostSettingsAuthority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteProcessAcceptanceTest {
    @TempDir Path directory;

    @Test
    void independentJvmPeersPersistOnlyOnHostAndSynchronizeCompletedEdits() throws Exception {
        Process host = start("host");
        Process client = null;
        try (var output = new BufferedReader(new InputStreamReader(host.getInputStream(), StandardCharsets.UTF_8));
             var control = new PrintWriter(host.getOutputStream(), true, StandardCharsets.UTF_8)) {
            String details = CompletableFuture.supplyAsync(() -> {
                try {
                    String line;
                    while ((line = output.readLine()) != null) if (line.startsWith("READY ")) return line.substring(6);
                    throw new IllegalStateException("Host exited before listening.");
                } catch (java.io.IOException failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }).get(25, TimeUnit.SECONDS);
            client = start("client");
            try (var input = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8)) {
                input.println(details);
                assertTrue(client.waitFor(45, TimeUnit.SECONDS), "Client process timed out.");
                assertEquals(0, client.exitValue(), () -> read("client-errors.log"));
            }
            control.println("STOP");
            assertTrue(host.waitFor(15, TimeUnit.SECONDS), "Host process did not stop.");
            assertEquals(0, host.exitValue(), () -> read("host-errors.log"));
            assertEquals("Written on A", Files.readString(directory.resolve("host.ini-operation")));
            assertTrue(Files.readString(directory.resolve("host.ini")).contains("0.6"));
            assertTrue(Files.readString(directory.resolve("client.ini")).contains("ui.gridOpacity = 0.3"));
            String measurement = read("loopback-measurement.txt");
            assertTrue(measurement.contains("24 completed edits"));
            System.out.println("Two-JVM loopback acceptance: " + measurement);
        } finally {
            if (client != null && client.isAlive()) client.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            if (host.isAlive()) host.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    private Process start(String mode) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", classpath, Peer.class.getName(), mode, directory.toString());
        command.redirectError(directory.resolve(mode + "-errors.log").toFile());
        if (mode.equals("client")) command.redirectOutput(directory.resolve("client-output.log").toFile());
        return command.start();
    }

    private String read(String name) {
        try {
            return Files.readString(directory.resolve(name));
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    public static final class Peer {
        public static void main(String[] arguments) throws Exception {
            Path root = Path.of(arguments[1]);
            try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                if (arguments[0].equals("host")) {
                    RemoteSessionTransportTest.createIdentity();
                    try (var host = new RemoteSessionTransportTest.Host(root.resolve("host.ini"))) {
                        System.out.println("READY " + host.server.port() + " "
                                + RemoteSessionTransportTest.identity.fingerprint() + " " + host.code());
                        System.out.flush();
                        host.approveWhenPending();
                        if (!"STOP".equals(input.readLine())) throw new IllegalStateException("Missing shutdown.");
                        assertEquals(24, host.owner.call(() -> host.project.getOverlays().size()));
                    }
                } else runClient(root, input.readLine().split(" "));
            }
        }

        private static void runClient(Path root, String[] details) throws Exception {
            Files.writeString(root.resolve("client.ini"), "ui.gridOpacity = 0.3\n");
            var settings = new AppSettings(root.resolve("client.ini"));
            byte[] localBefore = Files.readAllBytes(settings.getFile());
            try (var client = RemoteSessionClient.pair(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), Integer.parseInt(details[0])),
                    details[1], RemoteSessionTransportTest.BUILD, 5000, details[2], "Separate JVM")) {
                client.enableWorkspace();
                await(() -> client.latestWorkspace() != null);
                try (var coordinator = new RemoteSettingsClient(settings, client.latestState(), notice -> {})) {
                    settings.putDouble("ui.gridOpacity", 0.6);
                    var result = client.edit(coordinator.request()).get(10, TimeUnit.SECONDS);
                    assertEquals(HostSettingsAuthority.Status.ACCEPTED, result.status());
                    assertTrue(coordinator.receive(result));
                    var timings = new ArrayList<Long>();
                    for (int index = 0; index < 24; index++) {
                        var baseline = client.latestWorkspace();
                        var map = baseline.document().materialize(new dmmt.model.DmProject.CameraState());
                        map.getOverlays().add(dmmt.model.DmProject.OverlayShape.builder()
                                .id("process-shape-" + index).x(index * 10).build());
                        long started = System.nanoTime();
                        var accepted = client.editWorkspace(RemoteWorkspaceAuthority.diff(baseline,
                                RemoteProjectDocument.capture(map), "Complete shape")).get(10, TimeUnit.SECONDS);
                        assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, accepted.status());
                        timings.add(System.nanoTime() - started);
                    }
                    var baseline = client.operation(new RemoteOperations.Query(null, null, null, null))
                            .get(10, TimeUnit.SECONDS);
                    var command = new RemoteOperations.Command(baseline.sessionId(), UUID.randomUUID(),
                            baseline.nextSequence(), "test", "persist", dmmt.service.JsonMappers.create().nullNode());
                    RemoteOperations.Reply reply;
                    do {
                        reply = client.operation(new RemoteOperations.Query(command, null, null, null))
                                .get(10, TimeUnit.SECONDS);
                    } while (reply.status() == RemoteOperations.Status.RUNNING);
                    assertEquals(RemoteOperations.Status.ACCEPTED, reply.status());
                    assertEquals(reply, client.operation(new RemoteOperations.Query(command, null, null, null))
                            .get(10, TimeUnit.SECONDS));
                    timings.sort(Long::compareTo);
                    Files.writeString(root.resolve("loopback-measurement.txt"),
                            "24 completed edits; median=" + TimeUnit.NANOSECONDS.toMillis(timings.get(12))
                                    + "ms; p95=" + TimeUnit.NANOSECONDS.toMillis(timings.get(22)) + "ms");
                }
                assertFalse(settings.usesHostSettings());
                assertEquals(0.3, settings.getDouble("ui.gridOpacity", 0));
                assertArrayEquals(localBefore, Files.readAllBytes(settings.getFile()));
            }
        }

        private static void await(Callable<Boolean> condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!condition.call()) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("Peer synchronization timed out.");
                Thread.sleep(10);
            }
        }
    }
}
