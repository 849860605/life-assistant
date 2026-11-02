package com.lifeassistant.api.web;

import com.lifeassistant.api.auth.MediaAuth;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.file.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 文件上传接口：对话里上传图片、PDF、Word、Excel，识别成文字后随消息带给助手。
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final AuthFacade authFacade;
    private final MediaAuth mediaAuth;
    private final UploadService uploadService;

    /** POST /api/v1/files（multipart，字段名 file）：保存并识别，图片和扫描件要十几秒。 */
    @PostMapping
    public ApiResponse<UploadService.View> upload(@RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(uploadService.upload(authFacade.current(), file.getOriginalFilename(), file.getBytes()));
    }

    /** GET /api/v1/files/{id}/raw?mt=票据：原文件，图片和 PDF 在页面里直接打开，其他下载。 */
    @GetMapping("/{id}/raw")
    public ResponseEntity<byte[]> raw(@PathVariable String id, @RequestParam(required = false) String mt) throws IOException {
        UploadService.Opened file = uploadService.open(mediaAuth.user(mt).userId(), id);
        byte[] bytes;
        try (InputStream in = file.content()) {
            bytes = in.readAllBytes();
        }
        boolean inline = file.mime().startsWith("image/") || file.mime().equals("application/pdf");
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.name(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mime()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate()).body(bytes);
    }
}
