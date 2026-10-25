package com.ragkb.metrics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragkb.metrics.QaRecordEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 问答记录的写入。
 *
 * <p>与 {@link MetricsMapper} 分开:一个负责写(单条插入),一个负责读(聚合)。
 * 合成一个接口也能编译,但"写"和"统计"的生命周期不同 —— 将来把统计挪到只读库时,
 * 只需要换掉 MetricsMapper 的数据源,写入侧不受影响。
 */
@Mapper
public interface QaRecordMapper extends BaseMapper<QaRecordEntity> {
}
