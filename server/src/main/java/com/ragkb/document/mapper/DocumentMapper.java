package com.ragkb.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragkb.document.DocumentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DocumentMapper extends BaseMapper<DocumentEntity> {

    /**
     * 置状态并<b>清空</b>失败原因。
     *
     * <p>为什么不能复用 {@code updateById}:MyBatis-Plus 默认跳过 null 字段,
     * 于是"成功时把 fail_reason 置空"这个动作静默失效 —— 文档已经切好片了,
     * 界面上却还挂着上一次的失败原因。这类"字段没被更新"的问题不会报错,
     * 只有对着数据看才会发现。
     */
    @org.apache.ibatis.annotations.Update(
            "UPDATE documents SET status = #{status}, fail_reason = NULL, updated_at = #{now} WHERE id = #{id}")
    int updateStatusClearingReason(@Param("id") long id, @Param("status") String status,
                                   @Param("now") java.time.LocalDateTime now);

    /** 启动恢复用:找出停在中间态且长时间没有更新的文档。 */
    @org.apache.ibatis.annotations.Select(
            "SELECT * FROM documents WHERE status IN ('UPLOADED', 'PARSING') AND updated_at < #{deadline}")
    java.util.List<DocumentEntity> findStuck(@Param("deadline") java.time.LocalDateTime deadline);
}
