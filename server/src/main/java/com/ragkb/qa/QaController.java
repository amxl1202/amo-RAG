package com.ragkb.qa;

import com.ragkb.common.ApiException;
import com.ragkb.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问答接口。
 *
 * 这一层只做协议转换:请求体 → service → 响应体。编排逻辑全在 {@link QaService},
 * 因为跑批工具也要走同一套编排 —— 如果编排写在 controller 里,评估就会漏掉真实链路。
 */
@RestController
@RequestMapping("/api/qa")
public class QaController {

    /** 一次流式问答的最长挂起时间:生成 + 自检常态几秒,180 秒是兜底而非预期。 */
    private static final long STREAM_TIMEOUT_MILLIS = 180_000L;

    private static final tools.jackson.databind.json.JsonMapper MAPPER =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private final QaService qaService;
    private final QaStreamRegistry streamRegistry;
    private final java.util.concurrent.ExecutorService streamExecutor;

    public QaController(QaService qaService, QaStreamRegistry streamRegistry,
                        @org.springframework.beans.factory.annotation.Qualifier("qaStreamExecutor")
                        java.util.concurrent.ExecutorService streamExecutor) {
        this.qaService = qaService;
        this.streamRegistry = streamRegistry;
        this.streamExecutor = streamExecutor;
    }

    /**
     * 提问。
     *
     * <p>FAILED 单独转成 503:它意味着检索两路都挂了或模型调用失败,是"现在别重试太多次"
     * 的服务端问题;而 REFUSED 是正常业务结果,仍然 200。
     */
    /**
     * 流式提问(SSE)。
     *
     * <h2>事件协议</h2>
     * <pre>
     * event: open   data: {"streamId":"..."}          建立连接,给出可续传的流 ID
     * event: delta  data: {"text":"增量"}              生成中的增量(未定稿)
     * event: final  data: {完整 QaResponse}            自检后的定稿答案与引用
     * event: error  data: {"message":"...","retryable":true}
     * </pre>
     *
     * <p>前三者都用 {@code id:} 编号,客户端断线后带 {@code Last-Event-ID} 调
     * {@code GET /api/qa/stream/{streamId}} 补齐。之所以 delta 与 final 都推:
     * 生成几秒、自检还要一两秒,只推定稿等于放弃流式的意义;而只推 delta 又会让
     * "被删掉的句子"留在屏幕上 —— 两段式是唯一同时满足体感与可溯源的折中。
     */
    @PostMapping("/stream")
    public SseEmitter stream(@Valid @RequestBody QaRequest request) {
        String streamId = streamRegistry.open();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        // 无论是正常完成、超时还是客户端断开,都标记结束:缓冲保留到 TTL,允许重连取结果
        emitter.onCompletion(() -> streamRegistry.finish(streamId));
        emitter.onTimeout(() -> streamRegistry.finish(streamId));
        emitter.onError(error -> streamRegistry.finish(streamId));
        streamExecutor.submit(() -> runStream(streamId, request.question(), emitter));
        return emitter;
    }

    /**
     * 断线续传:补齐 {@code lastEventId} 之后的事件。
     *
     * <p>如果原流还在进行,补完历史后会再发一个 {@code replay-end} 事件并关闭连接 ——
     * 本实现不支持"接管进行中的流"(见 {@link QaStreamRegistry} 的类注释),
     * 客户端据此决定是继续等还是重新提问。
     */
    @GetMapping("/stream/{streamId}")
    public SseEmitter resume(@PathVariable String streamId,
                             @RequestParam(defaultValue = "0") long lastEventId) {
        SseEmitter emitter = new SseEmitter(30_000L);
        streamExecutor.submit(() -> {
            try {
                for (QaStreamRegistry.Event event : streamRegistry.since(streamId, lastEventId)) {
                    emitter.send(toSse(event));
                }
                if (!streamRegistry.isFinished(streamId)) {
                    emitter.send(SseEmitter.event().name("replay-end")
                            .data(json(java.util.Map.of("streamId", streamId))));
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** 真正跑一次问答并推送事件。全程单线程写 emitter,不需要额外同步。 */
    private void runStream(String streamId, String question, SseEmitter emitter) {
        try {
            send(emitter, streamId, "open", java.util.Map.of("streamId", streamId));
            QaAnswer answer = qaService.ask(question, delta -> {
                try {
                    send(emitter, streamId, "delta", java.util.Map.of("text", delta));
                } catch (java.io.IOException e) {
                    // 客户端断开:抛出以终止后续推送,避免继续为没人收的流烧 token
                    throw new java.io.UncheckedIOException(e);
                }
            });
            if (answer.status() == QaAnswer.Status.FAILED) {
                send(emitter, streamId, "error",
                        java.util.Map.of("message", "问答服务暂时不可用", "retryable", true));
            } else {
                send(emitter, streamId, "final", QaResponse.from(answer));
            }
            streamRegistry.finish(streamId);
            emitter.complete();
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            // 客户端已断开:静默结束,不写 error(没人收),但保留缓冲让重连能取到结果
            streamRegistry.finish(streamId);
        } catch (RuntimeException e) {
            try {
                send(emitter, streamId, "error",
                        java.util.Map.of("message", "生成失败,请重试", "retryable", true));
            } catch (Exception ignored) {
                // 发送失败说明连接已断,同上
            }
            streamRegistry.finish(streamId);
            emitter.complete();
        }
    }

    /**
     * 先写缓冲、再推给当前连接。
     *
     * <p>顺序不能反:先推后写的话,推完到写缓冲之间如果客户端断了,
     * 重连就补不到这条事件 —— 而那恰恰是最需要补的 {@code final}。
     */
    private void send(SseEmitter emitter, String streamId, String name, Object payload)
            throws java.io.IOException {
        QaStreamRegistry.Event event = streamRegistry.append(streamId, name, json(payload));
        // append 返回 null 说明缓冲已过期(30 秒后才可能),此时仍然把事件推给当前连接
        emitter.send(event == null
                ? SseEmitter.event().name(name).data(json(payload))
                : toSse(event));
    }

    private static SseEmitter.SseEventBuilder toSse(QaStreamRegistry.Event event) {
        return SseEmitter.event().id(String.valueOf(event.id())).name(event.name()).data(event.data());
    }

    private static String json(Object payload) {
        return MAPPER.writeValueAsString(payload);
    }

    @PostMapping
    public ApiResponse<QaResponse> ask(@Valid @RequestBody QaRequest request) {
        QaAnswer answer = qaService.ask(request.question());
        if (answer.status() == QaAnswer.Status.FAILED) {
            throw new ApiException(503, "问答服务暂时不可用,请稍后再试");
        }
        return ApiResponse.ok(QaResponse.from(answer));
    }
}
