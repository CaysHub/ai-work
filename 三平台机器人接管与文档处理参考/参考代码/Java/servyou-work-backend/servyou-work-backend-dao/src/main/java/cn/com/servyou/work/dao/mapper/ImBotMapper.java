package cn.com.servyou.work.dao.mapper;

import cn.com.servyou.work.dao.entity.ImBotEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.time.LocalDateTime;

@Mapper
public interface ImBotMapper extends BaseMapper<ImBotEntity> {
    LocalDateTime databaseNow();
    int claim(@Param("id") Long id, @Param("node") String node, @Param("seconds") int seconds);
    int renew(@Param("id") Long id, @Param("node") String node, @Param("version") Long version, @Param("seconds") int seconds);
    int release(@Param("id") Long id, @Param("node") String node, @Param("version") Long version);
    int connection(@Param("id") Long id, @Param("node") String node, @Param("version") Long version, @Param("state") String state, @Param("error") String error);
    /** 当前租约持有者按配置版本及旧密文更新令牌或发现水位，拒绝陈旧覆盖。 */
    int takeoverCheckpoint(@Param("bot") ImBotEntity bot, @Param("node") String node, @Param("version") Long version,
                           @Param("previous") String previous, @Param("cipher") String cipher);
    /** 接管失败后持久停用，只有人工修改或重新授权后才能恢复。 */
    int pauseTakeover(@Param("bot") ImBotEntity bot,@Param("node") String node,@Param("version") Long version,@Param("error") String error);
}
