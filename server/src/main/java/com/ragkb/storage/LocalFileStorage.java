package com.ragkb.storage;

import com.ragkb.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 本地磁盘存储:./data/files/{yyyy}/{MM}/{sha256}.{ext}
 * 按内容摘要命名 —— 同一内容天然只有一份,删除逻辑也因此可推理。
 */
@Slf4j
@Component
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(@Value("${app.storage.root:./data/files}") String rootDir) {
        this.root = Path.of(rootDir).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(String digestHex, String extension, InputStream content) {
        Path target = pathOf(digestHex, extension);
        try {
            Files.createDirectories(target.getParent());
            // 内容寻址意味着目标路径可预测:同内容重复写入用 REPLACE 覆盖,结果等价
            long size = Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            return new StoredFile(relative(target), size);
        } catch (IOException e) {
            log.error("store file failed: {}", target, e);
            throw new ApiException(500, "文件落盘失败");
        }
    }

    @Override
    public void delete(String storagePath) {
        try {
            Files.deleteIfExists(root.resolve(storagePath));
        } catch (IOException e) {
            // 删除失败不阻断主流程:留下的是孤儿文件,可由对账任务清理
            log.warn("delete file failed: {}", storagePath, e);
        }
    }

    @Override
    public InputStream open(String storagePath) {
        try {
            return Files.newInputStream(root.resolve(storagePath));
        } catch (IOException e) {
            throw new ApiException(404, "文件不存在或已被清理");
        }
    }

    private Path pathOf(String digestHex, String extension) {
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        return root.resolve(month).resolve(digestHex + "." + extension);
    }

    private String relative(Path absolute) {
        return root.relativize(absolute).toString().replace('\\', '/');
    }
}
