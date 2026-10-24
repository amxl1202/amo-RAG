package com.ragkb.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragkb.auth.RefreshTokenEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.UUID;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshTokenEntity> {

    /**
     * 原子地把一枚令牌标记为"已用"。
     *
     * <p>为什么要用 UPDATE ... WHERE used_at IS NULL,而不是"先查再改":
     * 同一枚令牌被并发提交两次(例如前端重试、或攻击者与用户同时刷新)时,
     * 先查再改会让两个请求都通过检查。数据库的条件更新只会让其中一个拿到 1 行,
     * 另一个拿到 0 行 —— 那正是重放信号。
     *
     * @return 1 表示抢到了这枚令牌,0 表示已被别人用过
     */
    @Update("UPDATE refresh_tokens SET used_at = #{now} WHERE id = #{id} AND used_at IS NULL")
    int claim(@Param("id") long id, @Param("now") LocalDateTime now);

    /** 吊销一整族:检测到重放时调用。 */
    @Update("UPDATE refresh_tokens SET revoked_at = #{now} "
            + "WHERE family_id = #{familyId} AND revoked_at IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") LocalDateTime now);

    /** 注销全部会话(退出登录、改密后调用)。 */
    @Update("UPDATE refresh_tokens SET revoked_at = #{now} "
            + "WHERE user_id = #{userId} AND revoked_at IS NULL")
    int revokeAllOfUser(@Param("userId") long userId, @Param("now") LocalDateTime now);
}
