package com.ragkb.common.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

/**
 * PostgreSQL {@code uuid} 列与 {@link UUID} 之间的转换。
 *
 * <h2>为什么必须自己写</h2>
 * MyBatis 3.5.19 自带的类型处理器里<b>没有</b> {@code UUID} 的（`org.apache.ibatis.type` 包下不存在
 * UUIDTypeHandler）。于是 {@code mapper.insert(entity)} 在绑定 {@code java.util.UUID} 参数时直接抛
 * {@code IllegalStateException: Type handler was null}，而不是给出任何一种可读的数据库错误。
 *
 * <h2>为什么不能"改成 String 传进去"</h2>
 * PG 的 {@code uuid} 列不会接受 {@code varchar} 参数（除非 JDBC URL 打开 {@code stringtype=unspecified}）。
 * 换句话说，把字段改成 String 只是把"绑定失败"换成"类型不匹配"，而且会让列的类型语义丢失。
 *
 * <h2>为什么 setObject 带 Types.OTHER</h2>
 * {@code uuid} 是 PG 的扩展类型，没有对应的 {@link Types} 常量。用 {@code Types.OTHER} 让驱动按
 * "未知类型"原样发送，PG 会以 uuid 接收；直接 {@code setString} 会被拒，{@code setObject(x, i)} 则依赖驱动推断。
 *
 * <h2>注册方式</h2>
 * 靠 {@code mybatis-plus.type-handlers-package} 全局扫描注册 —— 必须全局，因为除了 entity 的 insert，
 * {@code @Update} 里的 {@code UUID} 参数（如按族吊销）走的是另一条绑定路径，局部 {@code @TableField}
 * 只能救前者。
 */
@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    /** PG 驱动把 uuid 读成字符串，这里再转回 UUID；空值保持为空。 */
    private static UUID parse(String raw) {
        return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
    }
}
