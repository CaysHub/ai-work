package cn.com.servyou.work.dao.mapper;

import cn.com.servyou.work.dao.entity.ImBotSessionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.time.LocalDateTime;

@Mapper
public interface ImBotSessionMapper extends BaseMapper<ImBotSessionEntity> {
    /** 分页读取进度只允许当前接管配置及租约持有者推进。 */
    int takeoverCheckpoint(@org.apache.ibatis.annotations.Param("row") ImBotSessionEntity row,
                           @org.apache.ibatis.annotations.Param("bot") cn.com.servyou.work.dao.entity.ImBotEntity bot,
                           @org.apache.ibatis.annotations.Param("node") String node,@org.apache.ibatis.annotations.Param("version") Long version);
}
