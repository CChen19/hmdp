package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.utils.BlogImagePaths;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return Result.fail("文件为空");
        }
        if (image.getSize() > SystemConstants.MAX_IMAGE_UPLOAD_BYTES) {
            return Result.fail("文件过大");
        }
        String contentType = image.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return Result.fail("仅支持图片上传");
        }
        try {
            if (!isAllowedImageMagic(image)) {
                return Result.fail("文件内容不是合法图片");
            }
            String originalFilename = image.getOriginalFilename();
            String fileName = createNewFileName(originalFilename);
            File dest = BlogImagePaths.resolveSafeFile(SystemConstants.IMAGE_UPLOAD_DIR, fileName);
            if (dest == null) {
                return Result.fail("非法文件名");
            }
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return Result.fail("无法创建上传目录");
            }
            image.transferTo(dest);
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        try {
            File file = BlogImagePaths.resolveSafeFile(SystemConstants.IMAGE_UPLOAD_DIR, filename);
            if (file == null) {
                return Result.fail("错误的文件名称");
            }
            if (file.isDirectory()) {
                return Result.fail("错误的文件名称");
            }
            if (file.exists()) {
                FileUtil.del(file);
            }
            return Result.ok();
        } catch (IOException e) {
            return Result.fail("错误的文件名称");
        }
    }

    private String createNewFileName(String originalFilename) {
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        if (StrUtil.isBlank(suffix)) {
            suffix = "jpg";
        }
        suffix = suffix.replaceAll("[^A-Za-z0-9]", "");
        if (StrUtil.isBlank(suffix)) {
            suffix = "jpg";
        }
        String name = UUID.randomUUID().toString().replace("-", "");
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        String relativeDir = StrUtil.format("blogs/{}/{}", d1, d2);
        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR, relativeDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }

    private boolean isAllowedImageMagic(MultipartFile image) throws IOException {
        byte[] header = new byte[12];
        try (InputStream in = image.getInputStream()) {
            int read = in.read(header);
            if (read < 3) {
                return false;
            }
            // JPEG
            if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF) {
                return true;
            }
            // PNG
            if (read >= 8
                    && header[0] == (byte) 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47
                    && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A) {
                return true;
            }
            // GIF
            if (read >= 6
                    && header[0] == 'G' && header[1] == 'I' && header[2] == 'F'
                    && header[3] == '8' && (header[4] == '7' || header[4] == '9') && header[5] == 'a') {
                return true;
            }
            // WEBP: RIFF....WEBP
            if (read >= 12
                    && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
                return true;
            }
            return false;
        }
    }
}
