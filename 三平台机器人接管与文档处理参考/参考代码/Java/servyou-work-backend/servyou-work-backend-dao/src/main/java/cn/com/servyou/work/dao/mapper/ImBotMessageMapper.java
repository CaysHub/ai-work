package cn.com.servyou.work.dao.mapper;

import cn.com.servyou.work.dao.entity.ImBotMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.time.LocalDateTime;

@Mapper
public interface ImBotMessageMapper extends BaseMapper<ImBotMessageEntity> {
    /** 本人已回复时终止待处理或待发送行，保留原运行关联用于排查。 */
    int skipReplied(@Param("id") Long id,@Param("node") String node,@Param("version") Long version);
    List<ImBotMessageEntity> selectDispatch(@Param("botId") Long botId, @Param("limit") int limit);
    int acceptInbound(@Param("row") ImBotMessageEntity row, @Param("node") String node, @Param("version") Long version);
    int transition(@Param("id") Long id, @Param("node") String node, @Param("version") Long version,
                   @Param("expectedProcess") String expectedProcess, @Param("expectedSend") String expectedSend,
                   @Param("process") String process, @Param("send") String send);
    int progress(@Param("row") ImBotMessageEntity row, @Param("node") String node, @Param("version") Long version);
    /** 事件只保存运行关联和观察游标，不覆盖已发布正文及发送状态。 */
    int checkpoint(@Param("row") ImBotMessageEntity row,@Param("node") String node,@Param("version") Long version);
    /** 发布原运行的合并快照；保持在途发送，内容或状态变化时重新排队。 */
    int publish(@Param("row") ImBotMessageEntity row,@Param("node") String node,@Param("version") Long version);
    /** 回执只写发送字段，禁止回写陈旧执行状态、正文或问题进度。 */
    int acknowledge(@Param("row") ImBotMessageEntity row,@Param("state") String state,
                    @Param("providerId") String providerId,@Param("error") String error,
                    @Param("node") String node,@Param("version") Long version);
    /** 消费同一绑定用户及聊天的后续文字回答，不创建新的模型轮次。 */
    int consumeAnswer(@Param("id") Long id,@Param("sourceId") Long sourceId,
                      @Param("node") String node,@Param("version") Long version);
    /** 保存已上传的输入文档，只允许当前机器人归属及仍排队的消息写入一次。 */
    int attachFile(@Param("id") Long id,@Param("fileId") Long fileId,@Param("name") String name,
                   @Param("context") String context,@Param("node") String node,@Param("version") Long version);
    /** 复用消息表创建一条成果文件发送记录；相同逻辑文件不重置已有发送状态。 */
    int acceptResult(@Param("row") ImBotMessageEntity row,@Param("sourceId") Long sourceId,
                     @Param("node") String node,@Param("version") Long version);
}
