package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.constants.AgentEventType;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.api.model.agent.AgentEvent;
import cn.hutool.json.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实事件格式验证问题投影、编号转换和多题进度，不模拟平台返回成功。 */
class ImBotInteractionTest {
    private JSONObject question(boolean multiple) {
        return JSONUtil.createObj().set("question","查询哪个城市？").set("multiple",multiple)
                .set("options",JSONUtil.parseArray("[{\"label\":\"杭州\",\"description\":\"浙江\"},{\"label\":\"上海\"}]"));
    }
    private AgentEvent event(String status) {
        return AgentEvent.builder().type(AgentEventType.QUESTION_REQUEST).payload(JSONUtil.createObj()
                .set("requestId","que_test").set("status",status).set("directory","/internal/private-path")
                .set("questions",new JSONArray().put(question(false)).put(question(true)))).build();
    }
    @Test void resolvedEventsReplacePendingAndProjectionDoesNotLeakProtocol() {
        assertNull(ImBotInteraction.pending(Arrays.asList(event("pending"),event("answered"))));
        assertNull(ImBotInteraction.pending(Arrays.asList(event("pending"),event("rejected"))));
        JSONObject pending=ImBotInteraction.pending(Collections.singletonList(event("pending")));
        JSONObject state=JSONUtil.createObj().set("answers",new JSONArray());
        String prompt=ImBotInteraction.prompt(pending,state);
        assertTrue(prompt.contains("1/2"));assertTrue(prompt.contains("1. 杭州"));assertFalse(prompt.contains("internal"));assertFalse(prompt.contains("directory"));
        state.getJSONArray("answers").add(Collections.singletonList("杭州"));
        assertTrue(ImBotInteraction.prompt(pending,state).contains("2/2"));assertTrue(ImBotInteraction.prompt(pending,state).contains("可多选"));
        state.getJSONArray("answers").add(Arrays.asList("杭州","上海"));state.set("submission","UNKNOWN");
        assertTrue(ImBotInteraction.prompt(pending,state).contains("不会重复提交"));
    }
    @Test void selectionSupportsCustomTextMultipleOptionsAndRejectsInvalidIndexes() {
        assertEquals(Collections.singletonList("杭州"),ImBotInteraction.answer(question(false),"1"));
        assertEquals(Collections.singletonList("广州"),ImBotInteraction.answer(question(false),"广州"));
        assertEquals(Arrays.asList("杭州","上海"),ImBotInteraction.answer(question(true),"1，2,1"));
        assertThrows(BizException.class,()->ImBotInteraction.answer(question(false),"1,2"));
        assertThrows(BizException.class,()->ImBotInteraction.answer(question(false),"0"));
        assertThrows(BizException.class,()->ImBotInteraction.answer(question(false),"99999999999999"));
    }
    @Test void longReasoningCannotHideAnswerOrQuestionAndUnicodeRemainsValid() {
        StringBuilder longText=new StringBuilder();for(int i=0;i<7000;i++)longText.append("𠮷");
        String result=ImBotInteraction.render(longText.toString(),"杭州天气结果","请选择输出文件格式","session-test");
        assertTrue(result.contains("**思考**"));assertTrue(result.contains("杭州天气结果"));assertTrue(result.contains("请选择输出文件格式"));
        assertTrue(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=16000);
        assertFalse(result.contains("\uFFFD"));
    }
}
