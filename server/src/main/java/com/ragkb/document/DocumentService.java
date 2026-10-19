package com.ragkb.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragkb.common.ApiException;
import com.ragkb.document.mapper.DocumentMapper;
import com.ragkb.parse.ParserRegistry;
import com.ragkb.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 上传链路:校验 → 流式计算 SHA-256(写临时文件)→ 查重 → 落盘 → 入库。
 *
 * 失败补偿的取舍:
 * - 落盘成功但入库失败 → 删掉刚写的文件(确认没有记录指向它),保持"磁盘/DB 一致";
 * - 入库成功后的任何失败不回滚文件,状态机走 FAILED,文件仍然有主。
 * 并发同传同一文件:靠 sha256 唯一索引兜底,后到者收到 DuplicateKeyException 转 409。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentMapper documentMapper;
    private final FileStorage fileStorage;
    /**
     * 支持哪些扩展名只从解析器注册表取,不在本类另存一份。
     * 上一版这里硬编码了 {pdf, docx, md, txt},而注册表里其实还有 markdown ——
     * 结果是名为 .markdown 的文件解析得动却传不进来,且新增格式要改两处。
     */
    private final ParserRegistry parserRegistry;

    @Value("${app.upload.max-size-mb:50}")
    private int maxSizeMb;

    public DocumentEntity upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("上传内容为空");
        }
        String originalName = file.getOriginalFilename();
        String extension = extractExtension(originalName);
        Set<String> supported = parserRegistry.supportedExtensions();
        if (!supported.contains(extension)) {
            // 排序后再输出:集合本身无序,排序保证同样的错误每次提示一致
            throw ApiException.badRequest("不支持的文件类型: " + extension
                    + ",当前支持: " + new TreeSet<>(supported));
        }
        if (file.getSize() > maxSizeMb * 1024L * 1024L) {
            throw ApiException.badRequest("文件超过 " + maxSizeMb + "MB 限制");
        }

        // 流式边写边算摘要,避免 50MB 文件整体进堆内存
        Path tempFile = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            tempFile = Files.createTempFile("ragkb-upload-", ".bin");
            try (InputStream in = file.getInputStream()) {
                InputStream digesting = new java.security.DigestInputStream(in, digest);
                Files.copy(digesting, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());

            DocumentEntity existing = findBySha256(sha256);
            if (existing != null) {
                throw ApiException.conflict("文件已存在(文档 id=" + existing.getId() + ")");
            }

            FileStorage.StoredFile stored;
            try (InputStream in = Files.newInputStream(tempFile)) {
                stored = fileStorage.store(sha256, extension, in);
            }

            DocumentEntity entity = new DocumentEntity();
            entity.setOriginalName(originalName);
            entity.setSha256(sha256);
            entity.setFileSize(file.getSize());
            entity.setContentType(file.getContentType());
            entity.setStoragePath(stored.storagePath());
            entity.setStatus("UPLOADED");
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            try {
                documentMapper.insert(entity);
            } catch (DuplicateKeyException e) {
                // 并发上传同一文件:另一请求已入库。文件内容相同(内容寻址),无需清理
                throw ApiException.conflict("文件已存在(并发上传)");
            } catch (RuntimeException e) {
                // 入库失败且无记录指向该文件 → 回滚落盘,否则留下永远查不到的孤儿文件
                fileStorage.delete(stored.storagePath());
                throw e;
            }
            log.info("document uploaded: id={}, name={}, sha256={}...", entity.getId(), originalName,
                    sha256.substring(0, 8));
            // TODO(ingest): 上传到此为止,解析/切片/向量化都还没接进来 —— 文件落盘后不会再被处理。
            //  下一步应在这里投递异步任务,由后台推进 status(UPLOADED → PARSING → CHUNKED → … → READY);
            //  状态值已在 DocumentEntity 中定义,但只有 UPLOADED 被真正写入过。
            return entity;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        } catch (IOException e) {
            throw new ApiException(500, "读取上传内容失败");
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // 临时文件清理失败交给系统临时目录策略,不值得让请求失败
                }
            }
        }
    }

    public DocumentEntity getById(Long id) {
        DocumentEntity entity = documentMapper.selectById(id);
        if (entity == null) {
            throw ApiException.notFound("文档不存在: id=" + id);
        }
        return entity;
    }

    private DocumentEntity findBySha256(String sha256) {
        return documentMapper.selectOne(new LambdaQueryWrapper<DocumentEntity>()
                .eq(DocumentEntity::getSha256, sha256));
    }

    private String extractExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
