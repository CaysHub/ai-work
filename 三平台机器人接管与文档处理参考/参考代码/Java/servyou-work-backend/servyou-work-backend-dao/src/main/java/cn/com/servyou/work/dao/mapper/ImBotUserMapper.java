package cn.com.servyou.work.dao.mapper;

import cn.com.servyou.work.dao.entity.ImBotUserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ImBotUserMapper extends BaseMapper<ImBotUserEntity> {
    /** 原机器人使用工作台账号绑定，人员配置仅使用自身对应的绑定记录。 */
    @Select("SELECT u.* FROM sw_im_bot b JOIN sw_im_bot_user u ON u.tenant_id=b.tenant_id "
            +"AND ((b.parent_bot_id IS NULL AND u.bot_id=b.id) OR (u.bot_id=b.parent_bot_id AND u.id=b.id)) "
            +"WHERE b.id=#{key} AND u.user_id=#{userId}")
    ImBotUserEntity binding(@Param("key") Long key,@Param("userId") String userId);
}
