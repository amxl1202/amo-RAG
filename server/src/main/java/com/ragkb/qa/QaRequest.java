package com.ragkb.qa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提问请求。
 *
 * @param question 问题原文。上限 500 字:再长就不是"问题"而是"一段材料",
 *                 把它当问题去检索只会得到一个宽的离谱的向量,还白花一次调用
 */
public record QaRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 500, message = "问题不能超过 500 字")
        String question) {
}
