package com.lifeassistant.core.storage;

import com.lifeassistant.core.config.LifeProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 本地磁盘存储：文件放在 life.storage.local-dir 下，key 就是相对路径。 */
@Component
public class LocalStorageService implements StorageService {

    private final Path root;

    /** 根目录转成绝对规范路径，供 resolve 做越界检查。 */
    public LocalStorageService(LifeProperties properties) {
        this.root = Path.of(properties.getStorage().getLocalDir()).toAbsolutePath().normalize();
    }

    /** 自动创建父目录，同名文件直接覆盖。 */
    @Override
    public String put(String key, InputStream content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 调用方负责关闭返回的流。 */
    @Override
    public InputStream get(String key) {
        try {
            return Files.newInputStream(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 文件不存在时静默忽略。 */
    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 防止 key 里带 ../ 跳出存储目录 */
    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("非法的文件 key：" + key);
        }
        return path;
    }
}
