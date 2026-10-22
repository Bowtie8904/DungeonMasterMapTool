package dmmt.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsRemoteFirewallTest {
    @TempDir Path directory;

    @Test
    void queryingNeverElevatesAndDistinguishesProfileFromManagedPermission() throws Exception {
        var commands = new FakeCommands();
        var firewall = new WindowsRemoteFirewall(Path.of("D:\\dmmt\\DungeonMasterMapTool.exe"), commands);
        assertEquals(new RemoteNetworkAccess.Status(false, "Private"), firewall.check(configuration()));
        commands.result = "DMMT|Public|True\r\n";
        var status = firewall.check(configuration());
        assertTrue(status.configured());
        assertFalse(status.privateNetwork());
        assertTrue(commands.elevated.isEmpty());
        assertTrue(commands.queries.getFirst().contains("HNetCfg.FwPolicy2"));
        assertTrue(commands.queries.getFirst().contains("Get-NetConnectionProfile"));
        commands.result = "unexpected";
        assertThrows(IOException.class, () -> firewall.check(configuration()));
    }

    @Test
    void approvedRulesAreScopedIdempotentAndVerifiedWithNoJavaOrPublicPermission() throws Exception {
        var commands = new FakeCommands();
        commands.result = "DMMT|Private|True";
        var firewall = new WindowsRemoteFirewall(Path.of("D:\\DM's maps\\DungeonMasterMapTool.exe"), commands);
        firewall.allow(configuration());
        firewall.allow(configuration());
        assertEquals(commands.elevated.getFirst(), commands.elevated.getLast());
        String script = commands.elevated.getFirst();
        assertTrue(script.contains("'D:\\DM''s maps\\DungeonMasterMapTool.exe'"));
        assertTrue(script.contains("Profile = 'Private'"));
        assertTrue(script.contains("RemoteAddress = 'LocalSubnet'"));
        assertTrue(script.contains("Direction = 'Inbound'"));
        assertTrue(script.contains("@(7172,7173)"));
        assertTrue(script.contains("'UDP', @(7074)"));
        assertTrue(script.contains("Set-NetFirewallRule @parameters"));
        assertFalse(script.contains("Public"));
        assertFalse(script.contains("java.exe"));
        assertEquals(2, commands.queries.size(), "An elevated process success must be followed by an effective-rule check.");
        commands.result = "DMMT|Private|False";
        assertThrows(IOException.class, () -> firewall.allow(configuration()));
    }

    @Test
    void removalTargetsOnlyThisExecutableAndUnsupportedRuntimesNeverExecuteAnything() throws Exception {
        var commands = new FakeCommands();
        var firewall = new WindowsRemoteFirewall(Path.of("D:\\dmmt\\DungeonMasterMapTool.exe"), commands);
        firewall.remove();
        String removal = commands.elevated.getFirst();
        assertTrue(removal.contains("Remove-NetFirewallRule"));
        assertFalse(removal.contains(" -All"));
        assertTrue(removal.contains("'DMMT-LAN-"));
        assertTrue(removal.contains("rules could not be removed"));
        var moved = new FakeCommands();
        new WindowsRemoteFirewall(Path.of("E:\\dmmt\\DungeonMasterMapTool.exe"), moved).remove();
        assertNotEquals(removal, moved.elevated.getFirst());
        var unsupported = new WindowsRemoteFirewall(null, commands);
        assertFalse(unsupported.supported());
        int count = commands.elevated.size();
        assertThrows(IOException.class, () -> unsupported.allow(configuration()));
        assertThrows(IOException.class, () -> unsupported.check(configuration()));
        assertThrows(IOException.class, unsupported::remove);
        assertEquals(count, commands.elevated.size());
    }

    @Test
    void administratorCancellationPropagatesWithoutClaimingSuccess() throws Exception {
        var commands = new FakeCommands();
        commands.failure = new IOException("Administrator request cancelled.");
        var firewall = new WindowsRemoteFirewall(Path.of("D:\\dmmt\\DungeonMasterMapTool.exe"), commands);
        var failure = assertThrows(IOException.class, () -> firewall.allow(configuration()));
        assertSame(commands.failure, failure);
        assertTrue(commands.queries.isEmpty());
    }

    @Test
    void actualPowerShellProgressDoesNotContaminateStatusAndRealErrorsStillFail() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var commands = new WindowsRemoteFirewall.WindowsCommands();
        String script = """
                $ProgressPreference = 'Continue'
                Write-Progress -Activity 'Preparing modules for first use.' -Status 'Loading' -PercentComplete 50
                [Console]::Error.WriteLine('diagnostic-only-output')
                Write-Output 'DMMT|Private|True'
                exit 0
                """;
        assertEquals("DMMT|Private|True", commands.query(script).strip());
        var firewall = new WindowsRemoteFirewall(Path.of("D:\\dmmt\\DungeonMasterMapTool.exe"),
                new WindowsRemoteFirewall.Commands() {
                    @Override public String query(String ignored) throws IOException { return commands.query(script); }
                    @Override public void elevated(String ignored) { fail("Read-only status test must never elevate."); }
                });
        assertEquals(new RemoteNetworkAccess.Status(true, "Private"), firewall.check(configuration()));
        assertThrows(IOException.class, () -> commands.query(
                "[Console]::Error.WriteLine('Permission check failed'); Write-Output 'DMMT|Private|True'; exit 1"));
        assertEquals("DMMT|Private|False", commands.query(
                "[Console]::Error.Write('x' * 100000); Write-Output 'DMMT|Private|False'; exit 0").strip());
    }

    @Test
    void generatedScriptsParseInWindowsPowerShellWithoutExecutingFirewallCommands() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var commands = new FakeCommands();
        commands.result = "DMMT|Private|True";
        var firewall = new WindowsRemoteFirewall(Path.of("D:\\DM's maps\\DungeonMasterMapTool.exe"), commands);
        firewall.check(configuration());
        assertTrue(commands.queries.getFirst().contains("$ProgressPreference = 'SilentlyContinue'"));
        firewall.allow(configuration());
        firewall.remove();
        var scripts = new ArrayList<>(commands.queries);
        scripts.addAll(commands.elevated);
        for (int i = 0; i < scripts.size(); i++) {
            Path script = directory.resolve("firewall-" + i + ".ps1");
            Files.writeString(script, scripts.get(i));
            String parser = "$errors = $null; [System.Management.Automation.Language.Parser]::ParseFile('"
                    + script.toString().replace("'", "''")
                    + "', [ref]$null, [ref]$errors) | Out-Null; if ($errors.Count) { $errors; exit 1 }; exit 0";
            var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                    Base64.getEncoder().encodeToString(parser.getBytes(StandardCharsets.UTF_16LE)))
                    .redirectErrorStream(true).start();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private RemoteHostingController.Configuration configuration() throws Exception {
        return new RemoteHostingController.Configuration("Table", InetAddress.getByName("192.168.1.4"),
                7172, 7173, 1000, new RemoteSessionStorage(directory, 1024, 8192, 2048));
    }

    private static final class FakeCommands implements WindowsRemoteFirewall.Commands {
        final List<String> queries = new ArrayList<>();
        final List<String> elevated = new ArrayList<>();
        String result = "DMMT|Private|False";
        IOException failure;
        @Override public String query(String script) { queries.add(script); return result; }
        @Override public void elevated(String script) throws IOException {
            elevated.add(script);
            if (failure != null) throw failure;
        }
    }
}
