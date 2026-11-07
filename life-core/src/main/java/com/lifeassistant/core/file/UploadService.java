package com.lifeassistant.core.file;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户上传文件：存原文件、识别成文字；发消息时把识别出的文字拼在用户的话后面带给助手。
 * 只能用自己上传的文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    static final long MAX_BYTES = 15L * 1024 * 1024;
    /** 每人 24 小时内最多传多少个，看图模型按次计费 */
    static final int DAILY_LIMIT = 60;
    static final int MAX_TEXT = 30_000;
    /** 一条消息里每个文件、全部文件最多带多少字给助手 */
    static final int PER_FILE = 8_000;
    static final int PER_MESSAGE = 20_000;

    private static final Map<String, String> KIND_TEXT = Map.of("IMAGE", "图片", "PDF", "PDF", "WORD", "Word 文档",
            "EXCEL", "Excel 表格", "TEXT", "文本文件");

    private final UploadMapper uploadMapper;
    private final StorageService storage;
    private final FileTextReader reader;

    /** 上传结果：识别失败时 error 有值、chars 为 0；preview 是识别出的前几十个字。 */
    public record View(String id, String name, String kind, long size, Integer pages, int chars, String preview,
                       String error) {
    }

    /** 存文件并识别。类型不支持、太大、超过每天的次数直接报错，识别失败的照样保存，error 里说原因。 */
    public View upload(UserCtx ctx, String filename, byte[] bytes) {
        String name = cleanName(filename);
        UploadKind kind = UploadKind.of(name);
        if (kind == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, unsupported(UploadKind.ext(name)));
        }
        if (bytes.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件是空的");
        }
        if (bytes.length > MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件太大了，最大 15MB");
        }
        long recent = uploadMapper.selectCount(new LambdaQueryWrapper<UploadEntity>()
                .eq(UploadEntity::getUserId, ctx.userId())
                .gt(UploadEntity::getCreatedAt, OffsetDateTime.now().minusDays(1)));
        if (recent >= DAILY_LIMIT) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "今天上传的文件太多了，明天再传");
        }
        String id = UUID.randomUUID().toString();
        String key = "uploads/" + ctx.userId() + "/" + id + "." + UploadKind.ext(name);
        storage.put(key, new ByteArrayInputStream(bytes));

        UploadEntity row = new UploadEntity();
        row.setId(id);
        row.setUserId(ctx.userId());
        row.setName(name);
        row.setKind(kind.name());
        row.setSizeBytes((long) bytes.length);
        row.setStorageKey(key);
        try {
            FileTextReader.Result result = reader.read(kind, bytes, UploadKind.mime(name), ctx.userId());
            String text = result.text() == null ? "" : result.text().strip();
            row.setPages(result.pages());
            if (text.isEmpty()) {
                row.setError("没识别出文字");
            } else {
                row.setText(text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "\n（后面太长，没读）" : text);
            }
        } catch (BizException e) {
            row.setError(e.getMessage());
        } catch (Exception e) {
            log.warn("识别上传文件 {} 失败：{}", name, e.toString());
            row.setError("文件读不了，可能已损坏或设了密码");
        }
        uploadMapper.insert(row);
        return view(row);
    }

    /**
     * 把用户的话和附件拼成发给助手的一条消息。附件块的格式前端会认出来，显示成文件标签：
     * 〔附件：文件名｜id〕…〔附件结束〕。
     */
    public String compose(UserCtx ctx, String content, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return content;
        }
        StringBuilder out = new StringBuilder(content.strip());
        int budget = PER_MESSAGE;
        for (String id : ids.stream().distinct().toList()) {
            UploadEntity row = owned(ctx.userId(), id);
            out.append("\n\n〔附件：").append(row.getName()).append("｜").append(row.getId()).append("〕\n");
            if (row.getText() == null) {
                out.append("（这个文件没识别出来：").append(row.getError()).append("）\n");
            } else {
                String text = row.getText();
                int cap = Math.max(500, Math.min(PER_FILE, budget));
                if (text.length() > cap) {
                    text = text.substring(0, cap) + "\n（文件太长，后面没带上）";
                }
                budget -= text.length();
                out.append("（用户上传的").append(KIND_TEXT.get(row.getKind()))
                        .append("，下面是识别出的内容，可能有识别错误）\n").append(text).append('\n');
            }
            out.append("〔附件结束〕");
        }
        return out.toString();
    }

    /** 读原文件（给页面预览）；调用方关闭流。 */
    public Opened open(Long userId, String id) {
        UploadEntity row = owned(userId, id);
        return new Opened(row.getName(), UploadKind.mime(row.getName()), storage.get(row.getStorageKey()));
    }

    /** 校验文件是这个用户上传的，返回文件名（行程预订挂凭证时用）；不是或不存在报"文件不存在"。 */
    public String ownedName(Long userId, String id) {
        return owned(userId, id).getName();
    }

    /** 打开的原文件。 */
    public record Opened(String name, String mime, InputStream content) {
    }

    private UploadEntity owned(Long userId, String id) {
        UploadEntity row = id == null ? null : uploadMapper.selectById(id);
        if (row == null || !row.getUserId().equals(userId)) {
            throw BizException.notFound("文件");
        }
        return row;
    }

    private static View view(UploadEntity row) {
        String text = row.getText() == null ? "" : row.getText();
        String preview = text.replaceAll("\\s+", " ").strip();
        return new View(row.getId(), row.getName(), row.getKind(), row.getSizeBytes(), row.getPages(), text.length(),
                preview.length() > 60 ? preview.substring(0, 60) + "…" : preview, row.getError());
    }

    /** 文件名去掉路径和会和附件格式冲突的符号。 */
    static String cleanName(String filename) {
        String name = filename == null ? "" : filename.replaceAll(".*[/\\\\]", "").replaceAll("[〔〕｜\\r\\n\\t]", " ").strip();
        if (name.isEmpty()) {
            name = "未命名文件";
        }
        return name.length() > 120 ? name.substring(0, 60) + "…" + name.substring(name.length() - 40) : name;
    }

    private static String unsupported(String ext) {
        return switch (ext) {
            case "doc" -> "老版 Word（.doc）读不了，另存为 .docx 再传";
            case "heic", "heif" -> "HEIC 照片读不了，截个图或转成 JPG 再传";
            case "ppt", "pptx" -> "暂不支持 PPT，导出成 PDF 再传";
            default -> "支持图片、PDF、Word（.docx）、Excel、txt 文件";
        };
    }
}
