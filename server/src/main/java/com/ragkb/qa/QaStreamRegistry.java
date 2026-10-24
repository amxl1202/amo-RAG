package com.ragkb.qa;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式问答的事件缓冲:让断线重连能补齐错过的事件。
 *
 * <h2>为什么需要它</h2>
 * 一次问答要跑几秒(生成 + 自检),期间网络抖一下、浏览器切个后台,连接就可能断。
 * 断了之后服务端那次调用其实已经产生结果 —— 没有缓冲,这份结果就白扔了,
 * 用户还得再等一遍。缓冲 30 秒让客户端能带着 {@code Last-Event-ID} 回来取。
 *
 * <h2>为什么是"补历史"而不是"接管进行中的流"</h2>
 * 接管需要把"正在写事件的线程"与"新连接"解耦(发布订阅),而本项目一次问答
 * 只有一个写者、只有几秒 —— 为此引入消息总线不划算。所以语义定死为:
 * <b>重连补齐已发生的事件,若原流仍在进行,客户端会收到 {@code replay-end} 并自行决定是否重问。</b>
 * 这个限制写在类注释里,而不是留给调用方猜。
 *
 * <h2>缓冲为什么要有上限</h2>
 * 增量事件可能有上千条。{"最多存 2000 条 + 关键事件始终保留"} 的规则保证:
 * 极端长答案下内存不失控,而用户真正需要的那条 {@code final} 一定还在。
 */
@Component
public class QaStreamRegistry {

    /** 缓冲保留时长:超过就清掉。够覆盖一次重连,又不至于长期占内存。 */
    private static final Duration TTL = Duration.ofSeconds(30);

    /** 单个流最多保留多少个增量事件。 */
    private static final int MAX_EVENTS = 2000;

    private final Map<String, Buffer> buffers = new ConcurrentHashMap<>();

    /**
     * @param id   事件序号,从 1 开始,单调递增 —— 客户端的 Last-Event-ID 就是它
     * @param name SSE 事件名(open / delta / final / error)
     * @param data 已经序列化好的 JSON
     */
    public record Event(long id, String name, String data) {
    }

    /** 开一条新流,返回流 ID(客户端用它重连)。 */
    public String open() {
        pruneExpired();
        String streamId = UUID.randomUUID().toString();
        buffers.put(streamId, new Buffer());
        return streamId;
    }

    /** 追加一个事件。流不存在(已过期)时返回 null,调用方应当停止推送。 */
    public Event append(String streamId, String name, String data) {
        Buffer buffer = buffers.get(streamId);
        return buffer == null ? null : buffer.append(name, data);
    }

    /** 取 {@code id > lastEventId} 的事件;流不存在时返回空表。 */
    public List<Event> since(String streamId, long lastEventId) {
        Buffer buffer = buffers.get(streamId);
        return buffer == null ? List.of() : buffer.since(lastEventId);
    }

    /** 标记该流已结束。缓冲仍保留到 TTL,以便客户端重连取结果。 */
    public void finish(String streamId) {
        Buffer buffer = buffers.get(streamId);
        if (buffer != null) {
            buffer.finish();
        }
    }

    public boolean isFinished(String streamId) {
        Buffer buffer = buffers.get(streamId);
        return buffer == null || buffer.isFinished();
    }

    /** 仅供测试与排查:当前缓冲条数。 */
    int size() {
        return buffers.size();
    }

    private void pruneExpired() {
        Instant deadline = Instant.now().minus(TTL);
        buffers.entrySet().removeIf(entry -> entry.getValue().isExpired(deadline));
    }

    /** 一条流的事件缓冲。读写都在同一个实例锁上,量级小,不值得上更细的锁。 */
    private static final class Buffer {

        private final List<Event> events = new ArrayList<>();
        private final Instant createdAt = Instant.now();
        private long nextId = 1;
        private boolean finished;

        synchronized Event append(String name, String data) {
            Event event = new Event(nextId++, name, data);
            boolean isKeyEvent = "final".equals(name) || "error".equals(name);
            if (events.size() < MAX_EVENTS || isKeyEvent) {
                events.add(event);
            }
            return event;
        }

        synchronized List<Event> since(long lastEventId) {
            return events.stream().filter(event -> event.id() > lastEventId).toList();
        }

        synchronized void finish() {
            finished = true;
        }

        synchronized boolean isFinished() {
            return finished;
        }

        synchronized boolean isExpired(Instant deadline) {
            return createdAt.isBefore(deadline);
        }
    }
}
