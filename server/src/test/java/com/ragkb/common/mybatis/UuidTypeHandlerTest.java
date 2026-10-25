package com.ragkb.common.mybatis;

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UUID 类型处理器。
 *
 * <h2>这条用例为什么是被一个 P0 逼出来的</h2>
 * 之前 8 条刷新令牌用例全绿，是因为它们 mock 了 mapper —— MyBatis 的参数绑定这一层根本没被走过。
 * 真库上一点注册接口就 500。所以这里至少要把"绑定方式"和"读回解析"钉住：
 * <b>能断言的不多，但断言的这两点恰好就是当时错的地方。</b>
 */
class UuidTypeHandlerTest {

    @Test
    @DisplayName("参数用 setObject + Types.OTHER 绑定(uuid 没有对应的 Types 常量)")
    void bindsWithOtherType() throws Exception {
        UuidTypeHandler handler = new UuidTypeHandler();
        PreparedStatement ps = mock(PreparedStatement.class);
        UUID value = UUID.randomUUID();

        handler.setNonNullParameter(ps, 1, value, null);

        verify(ps).setObject(1, value, Types.OTHER);
    }

    @Test
    @DisplayName("读回时从字符串解析,空值保持为空")
    void parsesFromString() throws Exception {
        UuidTypeHandler handler = new UuidTypeHandler();
        ResultSet rs = mock(ResultSet.class);
        UUID value = UUID.randomUUID();

        when(rs.getString("family_id")).thenReturn(value.toString());
        when(rs.getString(2)).thenReturn(null);

        assertThat(handler.getNullableResult(rs, "family_id")).isEqualTo(value);
        assertThat(handler.getNullableResult(rs, 2)).isNull();
    }

    @Test
    @DisplayName("按 javaType 注册后,MyBatis 能找到 UUID 的处理器 —— 这正是 P0 缺的那一步")
    void registrationMakesUuidResolvable() {
        TypeHandlerRegistry registry = new Configuration().getTypeHandlerRegistry();
        assertThat(registry.hasTypeHandler(UUID.class)).as("MyBatis 默认没有 UUID 处理器").isFalse();

        registry.register(UuidTypeHandler.class);

        assertThat(registry.hasTypeHandler(UUID.class)).isTrue();
    }

    @Test
    @DisplayName("注册是按 javaType 生效的,不依赖具体属性名或 jdbcType")
    void resolutionDoesNotDependOnColumnName() throws Exception {
        TypeHandlerRegistry registry = new Configuration().getTypeHandlerRegistry();
        registry.register(UuidTypeHandler.class);

        assertThat(registry.getTypeHandler(UUID.class, null)).isInstanceOf(UuidTypeHandler.class);
        assertThat(registry.getTypeHandler(UUID.class, org.apache.ibatis.type.JdbcType.OTHER))
                .isInstanceOf(UuidTypeHandler.class);
    }
}
