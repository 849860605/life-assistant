package com.lifeassistant.core.storage;

import java.io.InputStream;

/** 文件存储：先存本地磁盘，以后换 OSS / MinIO。 */
public interface StorageService {

    /** 保存文件（同 key 覆盖），返回存储 key。 */
    String put(String key, InputStream content);

    /** 读取文件内容，调用方负责关闭流。 */
    InputStream get(String key);

    /** 删除文件，不存在时不报错。 */
    void delete(String key);
}
