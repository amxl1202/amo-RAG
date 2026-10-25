package com.ragkb.metrics;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次问答的落库记录。
 *
 * <p>生成与自检的 token 分开存,不是为了好看:自检那次调用的输入里有一整份材料,
 * 成本与生成同量级。只记生成的话,"平均单题成本"会少报近一半,而报表上完全看不出来。
 */
@Data
@TableName("qa_records")
public class QaRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String question;

    /** ANSWERED / REFUSED / FAILED。 */
    private String status;

    /** 拒答或失败的原因码;正常作答时为空。 */
    private String reason;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer checkPromptTokens;

    private Integer checkCompletionTokens;

    /** 检索耗时(含两路并行与融合)。 */
    private Integer retrievalMs;

    /** 生成 + 自检耗时。 */
    private Integer answerMs;

    private Integer citationCount;

    private String model;

    private LocalDateTime createdAt;
}
