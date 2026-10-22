package dmmt.remote;

import dmmt.log.Log;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Explicit, application-scoped permission for portable Windows builds, never a firewall bypass. */
public final class WindowsRemoteFirewall implements RemoteNetworkAccess {
    private static final Log LOG = Log.of(WindowsRemoteFirewall.class);
    interface Commands {
        String query(String script) throws IOException;
        void elevated(String script) throws IOException;
    }

    private final Path executable;
    private final Commands commands;

    public WindowsRemoteFirewall() {
        this(packagedExecutable(), new WindowsCommands());
    }

    WindowsRemoteFirewall(Path executable, Commands commands) {
        this.executable = executable;
        this.commands = commands;
    }

    private static Path packagedExecutable() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return null;
        return ProcessHandle.current().info().command().map(Path::of)
                .filter(path -> path.getFileName() != null
                        && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe"))
                .map(path -> path.toAbsolutePath().normalize()).orElse(null);
    }

    @Override public boolean supported() { return executable != null; }

    @Override public Status check(RemoteHostingController.Configuration configuration) throws IOException {
        requireSupported();
        String script = preamble() + """
                $ip = Get-NetIPAddress -AddressFamily IPv4 -IPAddress %s
                $profiles = @(Get-NetConnectionProfile -InterfaceIndex $ip.InterfaceIndex)
                $profile = if ($profiles.Count -eq 1) { [string]$profiles[0].NetworkCategory } else { 'Unknown' }
                $fw = New-Object -ComObject HNetCfg.FwPolicy2
                $allRules = @($fw.Rules)
                $configured = $true
                foreach ($entry in @(@('%s', 'TCP', 6, '%d,%d'), @('%s', 'UDP', 17, '7074'))) {
                    $matches = @($allRules | Where-Object { $_.Name -eq ('DMMT Private LAN ' + $entry[0]) })
                    if ($matches.Count -ne 1) { $configured = $false; continue }
                    $rule = $matches[0]
                    $actualPorts = (([string]$rule.LocalPorts).Split(',') | Sort-Object) -join ','
                    $expectedPorts = ($entry[3].Split(',') | Sort-Object) -join ','
                    if (-not $rule.Enabled -or $rule.Direction -ne 1 -or $rule.Action -ne 1 -or $rule.Profiles -ne 2 -or
                        $rule.ApplicationName -ne %s -or $rule.Protocol -ne $entry[2] -or
                        $actualPorts -ne $expectedPorts -or [string]$rule.RemoteAddresses -ne 'LocalSubnet') {
                        $configured = $false
                    }
                }
                Write-Output ('DMMT|' + $profile + '|' + $configured)
                """ .formatted(quote(configuration.bindAddress().getHostAddress()), ruleName("TCP"),
                configuration.controlPort(), configuration.assetPort(), ruleName("UDP"), quote(executable.toString()));
        String result = commands.query(script + epilogue()).strip();
        if (!result.matches("DMMT\\|(Private|Public|DomainAuthenticated|Unknown)\\|(True|False)")) {
            LOG.warn("Windows returned an unexpected firewall status: {}", RemoteSessionServer.safe(result));
            throw new IOException("Windows returned an unexpected network-access status. See application logs.");
        }
        String[] fields = result.split("\\|");
        LOG.debug("Firewall check: profile={}, configured={}", fields[1], fields[2]);
        return new Status("True".equals(fields[2]), fields[1]);
    }

    @Override public void allow(RemoteHostingController.Configuration configuration) throws IOException {
        requireSupported();
        String script = preamble() + """
                foreach ($entry in @(@('%s', 'TCP', @(%d,%d)), @('%s', 'UDP', @(7074)))) {
                    $parameters = @{
                        Name = $entry[0]
                        Direction = 'Inbound'; Action = 'Allow'; Enabled = 'True'
                        Profile = 'Private'; Program = %s; Protocol = $entry[1]
                        LocalPort = $entry[2]; RemoteAddress = 'LocalSubnet'; RemotePort = 'Any'; LocalAddress = 'Any'
                    }
                    $rule = Get-NetFirewallRule -Name $entry[0] -ErrorAction SilentlyContinue
                    if ($null -eq $rule) {
                        New-NetFirewallRule @parameters -DisplayName ('DMMT Private LAN ' + $entry[0]) `
                            -Description 'User-approved DMMT remote control. Private local subnet only.' | Out-Null
                    } else {
                        Set-NetFirewallRule @parameters -NewDisplayName ('DMMT Private LAN ' + $entry[0]) | Out-Null
                    }
                }
                """ .formatted(ruleName("TCP"), configuration.controlPort(), configuration.assetPort(),
                ruleName("UDP"), quote(executable.toString()));
        LOG.info("Requesting firewall rules for ports {} and {} (private local subnet only) for {}",
                configuration.controlPort(), configuration.assetPort(), executable);
        try {
            commands.elevated(script + epilogue());
        } catch (IOException failure) {
            LOG.warn("Adding firewall rules failed", failure);
            throw failure;
        }
        if (!check(configuration).configured()) {
            LOG.warn("Firewall rules were added but are not active; a system policy may prevent them");
            throw new IOException("Windows did not activate DMMT's private-LAN rules. A system policy may prevent them.");
        }
        LOG.info("Firewall rules added and verified");
    }

    @Override public void remove() throws IOException {
        requireSupported();
        LOG.info("Requesting removal of DMMT firewall rules for {}", executable);
        try {
            commands.elevated(preamble() + "Get-NetFirewallRule -Name " + quote(ruleName("TCP")) + ","
                + quote(ruleName("UDP")) + " -ErrorAction SilentlyContinue | Remove-NetFirewallRule\n"
                + "if (@(Get-NetFirewallRule -Name " + quote(ruleName("TCP")) + "," + quote(ruleName("UDP"))
                + " -ErrorAction SilentlyContinue).Count -ne 0) { throw 'DMMT rules could not be removed.' }\n"
                + epilogue());
        } catch (IOException failure) {
            LOG.warn("Removing firewall rules failed", failure);
            throw failure;
        }
        LOG.info("Firewall rules removed");
    }

    private void requireSupported() throws IOException {
        if (!supported()) throw new IOException(
                "Private-LAN permission is available in the packaged Windows app, not a shared Java runtime.");
    }

    private String ruleName(String protocol) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    executable.toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return "DMMT-LAN-" + HexFormat.of().formatHex(digest, 0, 12) + "-" + protocol;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable.", impossible);
        }
    }

    private static String quote(String text) { return "'" + text.replace("'", "''") + "'"; }
    private static String preamble() {
        return "$ErrorActionPreference = 'Stop'\n$ProgressPreference = 'SilentlyContinue'\ntry {\n";
    }
    private static String epilogue() { return "\n} catch { Write-Error $_ -ErrorAction Continue; exit 1 }\nexit 0\n"; }

    static final class WindowsCommands implements Commands {
        private Path powershell() throws IOException {
            String root = System.getenv("SystemRoot");
            if (root == null) throw new IOException("Windows system directory is unavailable.");
            return Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        }

        private String arguments(String script) {
            return "-NoLogo -NoProfile -NonInteractive -EncodedCommand "
                    + Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        }

        @Override public String query(String script) throws IOException {
            Process process = new ProcessBuilder(powershell().toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-EncodedCommand", Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
                    .start();
            var stdout = new java.util.concurrent.FutureTask<>(() -> readOutput(process.getInputStream()));
            var stderr = new java.util.concurrent.FutureTask<>(() -> readOutput(process.getErrorStream()));
            Thread.ofVirtual().name("dmmt-network-status").start(stdout);
            Thread.ofVirtual().name("dmmt-network-diagnostics").start(stderr);
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Windows network-access check timed out.");
                }
                String output = stdout.get(5, TimeUnit.SECONDS);
                String diagnostics = stderr.get(5, TimeUnit.SECONDS);
                if (process.exitValue() != 0) {
                    LOG.warn("Windows network check exited with {}.\n{}\n{}", process.exitValue(), output, diagnostics);
                    throw new IOException("Could not check Windows network access. See application logs.");
                }
                if (!diagnostics.isBlank()) {
                    LOG.debug("{}", diagnostics);
                }
                return output;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                throw new IOException("Could not read Windows network-access output.", failure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("Windows network-access check was interrupted.", interrupted);
            } finally {
                if (process.isAlive()) process.destroyForcibly();
                process.getInputStream().close();
                process.getErrorStream().close();
                process.getOutputStream().close();
            }
        }

        private static String readOutput(java.io.InputStream stream) throws IOException {
            try (stream) {
                var output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int count; (count = stream.read(buffer)) != -1;) {
                    int retained = Math.min(count, 16384 - output.size());
                    if (retained > 0) output.write(buffer, 0, retained);
                }
                return output.toString(StandardCharsets.UTF_8);
            }
        }

        @Override public void elevated(String script) throws IOException {
            var launch = new ShellAPI.SHELLEXECUTEINFO();
            launch.fMask = 0x00000040; // SEE_MASK_NOCLOSEPROCESS
            launch.lpVerb = "runas";
            launch.lpFile = powershell().toString();
            launch.lpParameters = arguments(script);
            launch.nShow = 0;
            if (!Shell32.INSTANCE.ShellExecuteEx(launch)) {
                int error = Native.getLastError();
                LOG.warn("Windows elevation was not granted (error {})", error);
                throw new IOException(error == 1223 ? "Windows administrator permission was cancelled. No access was granted."
                        : "Could not request Windows administrator permission (error " + error + ").");
            }
            if (launch.hProcess == null) throw new IOException("Windows did not return the permission helper process.");
            try {
                int wait = Kernel32.INSTANCE.WaitForSingleObject(launch.hProcess, 120_000);
                if (wait == 258) throw new IOException(
                        "The Windows permission request has not finished. Use Check access before trying again.");
                var exit = new IntByReference();
                if (wait != 0 || !Kernel32.INSTANCE.GetExitCodeProcess(launch.hProcess, exit)) {
                    throw new IOException("Could not read the Windows permission result. Use Check access.");
                }
                if (exit.getValue() != 0) {
                    LOG.warn("Firewall helper exited with code {}", exit.getValue());
                    throw new IOException(
                        "Windows could not update DMMT's firewall rules. Administrator permission or system policy may prevent changes.");
                }
            } finally {
                Kernel32.INSTANCE.CloseHandle(launch.hProcess);
            }
        }
    }
}
