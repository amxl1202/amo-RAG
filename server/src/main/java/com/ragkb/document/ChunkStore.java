package com.ragkb.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragkb.chunk.ChunkDraft;
import com.ragkb.document.mapper.DocumentChunkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 切片落库。
 *
 * 单独一个 bean,是因为事务边界应该只包"替换切片"这一段:
 * <ul>
 *   <li>解析和切片是纯计算 + 文件 IO,放进事务只会延长锁的持有时间;</li>
 *   <li>状态更新必须能独立提交 —— 如果和切片写入同一个事务,失败回滚会把
 *       {@code status=FAILED} 一起回滚掉,用户看到的是永远停在 PARSING 的文档。</li>
 * </ul>
 * 另外把事务方法挪出 {@link IngestService} 还避开了一个坑:
 * <b>同类自调用不会经过 Spring 代理,{@code @Transactional} 不生效</b>。
 *
 * 先删后插而不是追加:重新入库(改切片参数后重跑)必须替换,否则同一份文档会有两套切片
 * 同时被检索到。删与插在同一事务里,中途失败回到"没有这套新切片"的状态,而不是留下一半。
 */
@Service
@RequiredArgsConstructor
public class ChunkStore {

    private final DocumentChunkMapper chunkMapper;

    /**
     * 用新的切片整体替换某文档的旧切片。
     *
     * @return 写入条数
     */
    @Transactional
    public int replaceAll(long documentId, List<ChunkDraft> drafts) {
        chunkMapper.delete(new LambdaQueryWrapper<DocumentChunkEntity>()
                .eq(DocumentChunkEntity::getDocumentId, documentId));
        for (ChunkDraft draft : drafts) {
            DocumentChunkEntity entity = new DocumentChunkEntity();
            entity.setDocumentId(documentId);
            entity.setChunkIndex(draft.index());
            entity.setSectionPath(draft.sectionPath());
            entity.setHeadingLevel(draft.headingLevel());
            entity.setContent(draft.content());
            entity.setTokenCount(draft.tokenCount());
            entity.setCharStart(draft.charStart());
            entity.setCharEnd(draft.charEnd());
            entity.setPageStart(draft.pageStart());
            entity.setPageEnd(draft.pageEnd());
            chunkMapper.insert(entity);
        }
        return drafts.size();
    }
}
