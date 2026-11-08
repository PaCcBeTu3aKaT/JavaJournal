package ru.javajournal.core;

import org.junit.jupiter.api.Test;
import ru.javajournal.core.service.ReleaseUpdates;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseUpdatesTest {
    private static final String fixture = """
        {
          "tag_name": "v0.4.0", "html_url": "https://github.com/owner/JavaJournal/releases/tag/v0.4.0",
          "draft": false, "prerelease": false,
          "assets": [
            {"name": "JavaJournal-v0.4.0-windows-portable.zip", "browser_download_url": "https://github.com/owner/JavaJournal/releases/download/v0.4.0/win.zip"},
            {"name": "JavaJournal-v0.4.0-macos-intel.zip", "browser_download_url": "https://github.com/owner/JavaJournal/releases/download/v0.4.0/intel.zip"},
            {"name": "JavaJournal-v0.4.0-macos-arm64.zip", "browser_download_url": "https://github.com/owner/JavaJournal/releases/download/v0.4.0/arm.zip"}
          ]
        }
        """;

    @Test void repositoryMustBeFromGithub() {
        assertEquals("owner/JavaJournal", ReleaseUpdates.normalizeRepository("https://github.com/owner/JavaJournal/"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseUpdates.normalizeRepository("https://evil.example/owner/repo"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseUpdates.normalizeRepository("owner/repo/extra"));
    }

    @Test void versionComparisonsAreNumeric() {
        assertTrue(ReleaseUpdates.compareVersions("0.3.4", "v0.4.0") < 0);
        assertTrue(ReleaseUpdates.compareVersions("v1.10.0", "v1.9.0") > 0);
        assertEquals(0, ReleaseUpdates.compareVersions("v1.2", "1.2.0"));
    }

    @Test void selectsPlatformSpecificAsset() throws Exception {
        var windows = ReleaseUpdates.parseRelease(fixture, "owner/JavaJournal", "0.3.4", "Windows 11", "amd64");
        assertTrue(windows.available());
        assertTrue(windows.assetName().contains("windows"));

        var apple = ReleaseUpdates.parseRelease(fixture, "owner/JavaJournal", "0.3.4", "Mac OS X", "aarch64");
        assertEquals("JavaJournal-v0.4.0-macos-arm64.zip", apple.assetName());

        var intel = ReleaseUpdates.parseRelease(fixture, "owner/JavaJournal", "0.4.0", "Mac OS X", "x86_64");
        assertFalse(intel.available());
        assertEquals("JavaJournal-v0.4.0-macos-intel.zip", intel.assetName());
    }

    @Test void invalidAssetHostIsIgnored() throws Exception {
        String json = fixture.replace("https://github.com/owner/JavaJournal/releases/download/v0.4.0/win.zip", "https://bad.example/win.zip");
        var release = ReleaseUpdates.parseRelease(json, "owner/JavaJournal", "0.3.4", "Windows 11", "amd64");
        assertEquals("", release.downloadUrl());
    }
}
