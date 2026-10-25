package com.ragkb.document;

import com.ragkb.chunk.ChunkDraft;
import com.ragkb.chunk.ChunkSplitter;
import com.ragkb.document.mapper.DocumentMapper;
import com.ragkb.parse.ParsedDocument;
import com.ragkb.parse.ParserRegistry;
import com.ragkb.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 入库:把已上传的文件变成可检索的切片。
 *
 * 这条链路是"上传"与"检索"之间缺的那一段 —— 没有它,{@code document_chunks} 永远是空表,
 * 向量化没有数据可吃,评估也只能一直出 0。
 *
 * <h2>状态机</h2>
 * <pre>
 * UPLOADED --(开始)--> PARSING --(成功)--> CHUNKED
 *                          \--(失败)--> FAILED + fail_reason
 * </pre>
 * 失败原因必须落库:切片失败通常不是偶发网络问题,而是文件本身有问题(扫描件没有文字层、
 * 编码不对)。不记录的话,用户只能看到一个永远停在 PARSING 的文档,无从下手。
 *
 * <h2>为什么本类不加 @Transactional</h2>
 * 见 {@link ChunkStore} 的注释:事务只包切片替换;状态更新要能独立提交,
 * 否则失败回滚会把 FAILED 一起抹掉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestService {

    private final DocumentMapper documentMapper;
    private final ChunkStore chunkStore;
    private final FileStorage fileStorage;
    private final ParserRegistry parserRegistry;
    private final ChunkSplitter chunkSplitter;

    /** 入库结果:切片数与最终状态。 */
    public record IngestResult(long documentId, int chunkCount, String status) {
    }

    /**
     * 解析并切片一个文档,替换它已有的切片。
     *
     * @throws com.ragkb.common.ApiException 文档不存在(404)
     */
    public IngestResult ingest(long documentId) {
        DocumentEntity document = documentMapper.selectById(documentId);
        if (document == null) {
            throw com.ragkb.common.ApiException.notFound("文档不存在: id=" + documentId);
        }
        updateStatus(documentId, "PARSING", null);
        try {
            ParsedDocument parsed = parse(document);
            List<ChunkDraft> drafts = chunkSplitter.split(parsed);
            if (drafts.isEmpty()) {
                // 解析出 0 个块几乎总是"文件有问题"(空文件、纯图片扫描件),不该当成功
                throw new IllegalStateException("解析结果为空,文件可能没有可提取的文字层");
            }
            int written = chunkStore.replaceAll(documentId, drafts);
            updateStatus(documentId, "CHUNKED", null);
            log.info("document ingested: id={}, name={}, chunks={}",
                    documentId, document.getOriginalName(), written);
            return new IngestResult(documentId, written, "CHUNKED");
        } catch (RuntimeException e) {
            String reason = abbreviate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            updateStatus(documentId, "FAILED", reason);
            log.warn("document ingest failed: id={}, name={}, reason={}",
                    documentId, document.getOriginalName(), reason);
            throw e;
        }
    }

    /**
     * 异步入口:吞掉异常只记日志。
     *
     * 由上传流程调用 —— 上传请求不该等着解析完成(20 页 PDF 要几百毫秒到几秒)。
     * 失败信息已经写进 status / fail_reason,这里只负责不让异常逃到线程池外面。
     */
    public void ingestQuietly(long documentId) {
        try {
            ingest(documentId);
        } catch (RuntimeException e) {
            log.debug("ingest quietly swallowed: id={}, reason={}", documentId, e.toString());
        }
    }

    private ParsedDocument parse(DocumentEntity document) {
        String extension = extensionOf(document.getOriginalName());
        try (InputStream in = fileStorage.open(document.getStoragePath())) {
            return ParsedDocument.of(parserRegistry.require(extension).parse(in));
        } catch (IOException e) {
            throw new UncheckedIOException("读取存储文件失败: " + document.getStoragePath(), e);
        }
    }

    /**
     * 更新状态。
     *
     * <p>失败原因为空时必须走显式 SQL:MyBatis-Plus 的 {@code updateById} 跳过 null 字段,
     * 用它会"清不掉"上一次的失败原因 —— 文档已恢复正常,界面上还留着旧的报错。
     */
    private void updateStatus(long documentId, String status, String failReason) {
        LocalDateTime now = LocalDateTime.now();
        if (failReason == null) {
            documentMapper.updateStatusClearingReason(documentId, status, now);
            return;
        }
        DocumentEntity patch = new DocumentEntity();
        patch.setId(documentId);
        patch.setStatus(status);
        patch.setFailReason(failReason);
        patch.setUpdatedAt(now);
        documentMapper.updateById(patch);
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String abbreviate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
