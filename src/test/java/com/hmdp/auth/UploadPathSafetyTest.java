package com.hmdp.auth;

import com.hmdp.controller.UploadController;
import com.hmdp.dto.Result;
import com.hmdp.utils.BlogImagePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upload path traversal / absolute path rejection (no Redis/MySQL).
 */
class UploadPathSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsDotDotAndAbsolutePaths() throws Exception {
        String base = tempDir.toFile().getCanonicalPath();
        assertNull(BlogImagePaths.resolveSafeFile(base, "../etc/passwd"));
        assertNull(BlogImagePaths.resolveSafeFile(base, "/etc/passwd"));
        assertNull(BlogImagePaths.resolveSafeFile(base, "/tmp/evil.jpg"));
        assertNull(BlogImagePaths.resolveSafeFile(base, "C:/Windows/System32/a.jpg"));
        assertFalse(BlogImagePaths.isAllowedRelativeName("../secret"));
        assertFalse(BlogImagePaths.isAllowedRelativeName("/etc/passwd"));
    }

    @Test
    void acceptsAppRelativeBlogNameUnderRoot() throws Exception {
        String base = tempDir.toFile().getCanonicalPath();
        String relative = "/blogs/10/15/0123456789abcdef0123456789abcdef.jpg";
        Path target = tempDir.resolve("blogs/10/15");
        Files.createDirectories(target);
        Files.write(target.resolve("0123456789abcdef0123456789abcdef.jpg"), new byte[]{1, 2, 3});
        File resolved = BlogImagePaths.resolveSafeFile(base, relative);
        assertNotNull(resolved);
        assertTrue(resolved.getCanonicalPath().startsWith(base));
    }

    @Test
    void controllerDeleteRejectsTraversal() {
        UploadController controller = new UploadController();
        Result r1 = controller.deleteBlogImg("../etc/passwd");
        assertFalse(Boolean.TRUE.equals(r1.getSuccess()));
        Result r2 = controller.deleteBlogImg("/tmp/evil.jpg");
        assertFalse(Boolean.TRUE.equals(r2.getSuccess()));
        Result r3 = controller.deleteBlogImg("C:\\Windows\\a.jpg");
        assertFalse(Boolean.TRUE.equals(r3.getSuccess()));
    }
}
