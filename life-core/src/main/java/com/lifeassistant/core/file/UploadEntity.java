package com.lifeassistant.core.file;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** t_upload 表实体：用户上传的一个文件和识别出的文字。 */
@Data
@TableName("t_upload")
public class UploadEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long userId;
    private String name;
    private String kind;
    private Long sizeBytes;
    private String storageKey;
    private String text;
    private Integer pages;
    private String error;
    private OffsetDateTime createdAt;
}
