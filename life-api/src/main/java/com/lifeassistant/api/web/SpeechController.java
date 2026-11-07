package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.speech.SpeechRecognizer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/** 语音输入（需要登录）：上传一段 WAV 录音，返回转写的文字，前端放进输入框让用户看过再发。 */
@RestController
@RequestMapping("/api/v1/speech")
@RequiredArgsConstructor
public class SpeechController {

    private final AuthFacade authFacade;
    private final SpeechRecognizer recognizer;

    /** POST /api/v1/speech/transcribe（multipart，字段 file）：返回 {text}，没听到说话时 text 为空。 */
    @PostMapping("/transcribe")
    public ApiResponse<Map<String, String>> transcribe(@RequestParam("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "录音没传上来，再试一次");
        }
        return ApiResponse.ok(Map.of("text", recognizer.transcribe(authFacade.current().userId(), bytes)));
    }
}
