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
    private final IngestTrigger ingestTrigger;

    @Value("${app.upload.max-size-mb:50}")
    private int maxSizeMb;

    /**
     * 上传。
     *
     * @param tenantId 所属租户。去重也必须在租户内做:<b>全局去重会泄漏"别人有这份文件"</b>
     *                 (B 传同名文件收到 409,等于确认了 A 的存在),同时会挡住 B 的正常上传。
     */
    public DocumentEntity upload(MultipartFile file, long tenantId) {
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

            DocumentEntity existing = findBySha256(sha256, tenantId);
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
            entity.setTenantId(tenantId);
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
            // 解析与切片交给后台:上传请求不该等它跑完。
            // 状态推进(UPLOADED → PARSING → CHUNKED / FAILED)由 IngestService 负责。
            ingestTrigger.trigger(entity.getId());
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

    /**
     * 按 id 取文档,且必须属于该租户。
     *
     * <p>跨租户访问返回 <b>404 而不是 403</b>:403 等于告诉对方"这个 id 是存在的",
     * 那本身就是一条信息。对外一律表现为"没有这个东西"。
     */
    public DocumentEntity getById(Long id, long tenantId) {
        DocumentEntity entity = documentMapper.selectOne(new LambdaQueryWrapper<DocumentEntity>()
                .eq(DocumentEntity::getId, id)
                .eq(DocumentEntity::getTenantId, tenantId));
        if (entity == null) {
            throw ApiException.notFound("文档不存在: id=" + id);
        }
        return entity;
    }

    /**
     * 分页列出本租户的文档。
     *
     * <p>为什么不再"一次返回全部":文档攒到几百条时,响应体里每条还带着 64 字符的 sha256,
     * 一次就是几十 KB,而前端只需要当前页。分页参数在 {@link com.ragkb.common.PageResult}
     * 里收敛过范围,避免 size 被传成天文数字。
     *
     * @param status 可选的状态过滤(如只看 FAILED);为空表示不过滤
     */
    public com.ragkb.common.PageResult<DocumentEntity> list(long tenantId, int page, int size, String status) {
        int safePage = com.ragkb.common.PageResult.normalizePage(page);
        int safeSize = com.ragkb.common.PageResult.normalizeSize(size);
        LambdaQueryWrapper<DocumentEntity> condition = new LambdaQueryWrapper<DocumentEntity>()
                .eq(DocumentEntity::getTenantId, tenantId);
        if (status != null && !status.isBlank()) {
            condition.eq(DocumentEntity::getStatus, status.trim().toUpperCase(java.util.Locale.ROOT));
        }
        long total = documentMapper.selectCount(condition);
        java.util.List<DocumentEntity> items = documentMapper.selectList(condition
                // 显式拼 limit/offset:项目没有装配分页插件,多引一个拦截器只为这一处不值当;
                // 两个值都已在上面收敛成受控整数,不存在注入面
                .orderByDesc(DocumentEntity::getCreatedAt)
                .last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize));
        return new com.ragkb.common.PageResult<>(items, total, safePage, safeSize);
    }

    private DocumentEntity findBySha256(String sha256, long tenantId) {
        return documentMapper.selectOne(new LambdaQueryWrapper<DocumentEntity>()
                .eq(DocumentEntity::getSha256, sha256)
                .eq(DocumentEntity::getTenantId, tenantId));
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
