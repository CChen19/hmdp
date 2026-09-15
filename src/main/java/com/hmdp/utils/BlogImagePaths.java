package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Validates blog image relative names under the upload root (no path traversal).
 */
public final class BlogImagePaths {

    /** Matches names produced by {@code UploadController#createNewFileName} (d1/d2 are 0–15). */
    private static final Pattern BLOG_RELATIVE =
            Pattern.compile("^/blogs/[0-9]{1,2}/[0-9]{1,2}/[0-9a-fA-F]+\\.[A-Za-z0-9]+$");

    private BlogImagePaths() {
    }

    public static boolean isAllowedRelativeName(String filename) {
        if (StrUtil.isBlank(filename)) {
            return false;
        }
        if (filename.indexOf('\\') >= 0 || filename.contains("..")) {
            return false;
        }
        // Windows drive-absolute
        if (filename.length() >= 2
                && Character.isLetter(filename.charAt(0))
                && filename.charAt(1) == ':') {
            return false;
        }
        // Unix absolute outside our /blogs/... convention
        if (filename.startsWith("/") && !BLOG_RELATIVE.matcher(filename).matches()) {
            return false;
        }
        return BLOG_RELATIVE.matcher(filename).matches();
    }

    /**
     * Resolves a safe file under {@code uploadDir}, or {@code null} if rejected.
     * Leading {@code /} on app-relative names is stripped so {@link File} does not treat them as absolute.
     */
    public static File resolveSafeFile(String uploadDir, String filename) throws IOException {
        if (!isAllowedRelativeName(filename)) {
            return null;
        }
        String relative = filename.startsWith("/") ? filename.substring(1) : filename;
        File base = new File(uploadDir).getCanonicalFile();
        File target = new File(base, relative).getCanonicalFile();
        String basePath = base.getPath();
        String targetPath = target.getPath();
        if (!targetPath.startsWith(basePath + File.separator) && !targetPath.equals(basePath)) {
            return null;
        }
        return target;
    }
}
