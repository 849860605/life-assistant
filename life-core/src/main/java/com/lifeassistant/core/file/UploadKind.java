package com.lifeassistant.core.file;

import java.util.Locale;
import java.util.Map;

/** 支持上传的文件类型，按扩展名判断。 */
public enum UploadKind {
    IMAGE, PDF, WORD, EXCEL, TEXT;

    private static final Map<String, UploadKind> BY_EXT = Map.ofEntries(
            Map.entry("jpg", IMAGE), Map.entry("jpeg", IMAGE), Map.entry("png", IMAGE), Map.entry("webp", IMAGE),
            Map.entry("bmp", IMAGE), Map.entry("gif", IMAGE),
            Map.entry("pdf", PDF), Map.entry("docx", WORD), Map.entry("xlsx", EXCEL), Map.entry("xls", EXCEL),
            Map.entry("txt", TEXT), Map.entry("md", TEXT), Map.entry("csv", TEXT));

    private static final Map<String, String> MIME = Map.of("jpg", "image/jpeg", "jpeg", "image/jpeg",
            "png", "image/png", "webp", "image/webp", "bmp", "image/bmp", "gif", "image/gif", "pdf", "application/pdf");

    /** 小写扩展名，没有返回空串。 */
    public static String ext(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 不支持的扩展名返回 null。 */
    public static UploadKind of(String name) {
        return BY_EXT.get(ext(name));
    }

    /** 原文件回给浏览器时用的类型。 */
    public static String mime(String name) {
        return MIME.getOrDefault(ext(name), "application/octet-stream");
    }
}
