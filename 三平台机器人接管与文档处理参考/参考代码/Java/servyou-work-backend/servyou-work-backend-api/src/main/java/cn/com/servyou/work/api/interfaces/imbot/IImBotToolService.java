package cn.com.servyou.work.api.interfaces.imbot;

import cn.com.servyou.work.api.model.imbot.ImBotToolDTO;
import cn.com.servyou.work.api.model.imbot.ImBotToolRequestDTO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import java.util.Map;

/** 以当前用户获准使用的机器人授权调用企微七类平台工具。 */
public interface IImBotToolService {
    /** 实时发现指定能力的工具，按关键词或工具名筛选并分页返回定义。 */
    IPage<ImBotToolDTO> page(ImBotToolRequestDTO request);
    /** 执行当前授权目录中的一个工具，不接受自定义地址或身份。 */
    Map<String,Object> execute(ImBotToolRequestDTO request);
}
