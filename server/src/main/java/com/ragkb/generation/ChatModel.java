package com.ragkb.generation;

/**
 * 对话模型的最小抽象:两段提示词进,一段正文出。
 *
 * <h2>为什么是函数式接口</h2>
 * 生成层的每个决策(引用编号怎么解析、无证据的句子怎么剔除、拒答怎么措辞)都需要
 * "模型说了什么"作为输入,而这些决策本身不该依赖 HTTP。把模型缩成一句话的接口,
 * 测试里就能用 lambda 直接扮演模型:返回一段带 {@code [E9]} 的答案、返回空串、
 * 返回拒答固定串 —— 这些分支用真实接口几乎造不出来。
 *
 * <h2>为什么只给 system + user 两段</h2>
 * 单轮问答用不到多轮消息列表。真要做对话记忆时应该新建一个带消息列表的接口,
 * 而不是把这个方法的参数从两个字符串改成一个列表 —— 那会让现有所有调用点一起改,
 * 而多轮与单轮的提示词拼装规则本就不同(历史消息要不要带编号?要不要重新编号?)。
 */
@FunctionalInterface
public interface ChatModel {

    /**
     * @param systemPrompt 系统提示词,承载"必须按材料作答、必须标编号"这类硬规则
     * @param userPrompt   用户提示词,含问题与带编号的材料
     * @return 模型输出与用量
     * @throws ChatException 调用失败或响应不可解析
     */
    ChatResponse complete(String systemPrompt, String userPrompt);
}
