package com.ragkb.common;

import java.util.List;

/**
 * 分页结果。
 *
 * <h2>为什么带 total</h2>
 * 只给 items 的分页，前端无法画页码，只能"下一页/上一页"地猜。
 * 但 total 也有代价：每次都要多一次 count 查询。当前数据量下可以忽略，
 * 若将来文档到了十万级，就该改成"不返回精确 total，或由缓存提供"。
 *
 * @param items 当前页数据
 * @param total 满足条件的总条数
 * @param page  页码，从 1 开始
 * @param size  每页条数
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    /** 最大页大小：再大就失去分页的意义，还会把响应体撑大。 */
    public static final int MAX_SIZE = 200;

    /** 收敛非法入参：页码至少 1，页大小落在 [1, MAX_SIZE]，避免调用方把 size 传成 100000。 */
    public static int normalizePage(int page) {
        return Math.max(1, page);
    }

    public static int normalizeSize(int size) {
        if (size <= 0) {
            return 50;
        }
        return Math.min(size, MAX_SIZE);
    }
}
