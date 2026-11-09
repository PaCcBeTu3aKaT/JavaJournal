package ru.javajournal.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.prefs.Preferences;

/** Per-user credential storage. Windows: DPAPI; macOS: user's Keychain.
 * On other OSes no password is stored (fail closed, never plaintext fallback).
 */
final class CredentialStore {
    record Credentials(String username, String password, String token) { }
    private static final String service = "ru.javajournal.credentials";
    private static final Preferences prefs = Preferences.userNodeForPackage(CredentialStore.class);
    private static final String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    private CredentialStore() { }

    static void save(String username, String password, String token) throws Exception {
        String packed = Base64.getEncoder().encodeToString((username + "\u0000" + password + "\u0000" + token)
            .getBytes(StandardCharsets.UTF_8));
        if (os.contains("win")) {
            String encoded = runPowerShell("Add-Type -AssemblyName System.Security; "
                + "$s=[Console]::In.ReadToEnd(); $b=[Text.Encoding]::UTF8.GetBytes($s); "
                + "$r=[Security.Cryptography.ProtectedData]::Protect($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser); "
                + "[Console]::Out.Write([Convert]::ToBase64String($r))", packed);
            prefs.put("dpapiCredentials", encoded);
            prefs.flush();
        } else if (os.contains("mac")) {
            // Credentials are supplied via stdin to the interactive security tool, never in process arguments.
            run(new String[]{"security", "-i"},
                "add-generic-password -s " + service + " -a JavaJournal -w " + packed + " -U\nquit\n");
        } else throw new IOException("Защищённое сохранение не поддерживается на этой ОС.");
    }
    static Credentials load() {
        try {
            String packed;
            if (os.contains("win")) {
                String encrypted = prefs.get("dpapiCredentials", "");
                if (encrypted.isBlank()) return null;
                packed = runPowerShell("Add-Type -AssemblyName System.Security; "
                    + "$s=[Console]::In.ReadToEnd(); "
                    + "$r=[Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String($s),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser); "
                    + "[Console]::Out.Write([Text.Encoding]::UTF8.GetString($r))", encrypted);
            } else if (os.contains("mac")) {
                packed = run(new String[]{"security", "find-generic-password", "-s", service,
                    "-a", "JavaJournal", "-w"}, "");
            } else return null;
            String[] fields = new String(Base64.getDecoder().decode(packed.trim()), StandardCharsets.UTF_8)
                .split("\u0000", 3);
            return fields.length == 3 ? new Credentials(fields[0], fields[1], fields[2]) : null;
        } catch (Exception ignored) { return null; }
    }
    static void clear() {
        try {
            if (os.contains("win")) {prefs.remove("dpapiCredentials");prefs.flush();}
            else if (os.contains("mac")) run(new String[]{"security", "delete-generic-password",
                "-s", service, "-a", "JavaJournal"}, "");
        } catch (Exception ignored) { }
    }
    private static String runPowerShell(String script, String stdin) throws Exception {
        String command = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return run(new String[]{"powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", command}, stdin);
    }
    private static String run(String[] args, String input) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        try (var out = process.getOutputStream()) {out.write(input.getBytes(StandardCharsets.UTF_8));}
        String output;
        try (var in = process.getInputStream()) {output = new String(in.readAllBytes(), StandardCharsets.UTF_8);}
        if (process.waitFor() != 0) throw new IOException("OS credential store unavailable");
        return output.trim();
    }
}
