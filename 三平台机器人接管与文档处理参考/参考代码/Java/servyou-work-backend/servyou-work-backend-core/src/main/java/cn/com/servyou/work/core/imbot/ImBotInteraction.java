package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.constants.AgentEventType;
import cn.com.servyou.work.api.enums.ImBotEnums.QuestionStatus;
import cn.com.servyou.work.api.enums.ImBotEnums.QuestionSubmission;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.api.model.agent.AgentEvent;
import cn.hutool.json.*;
import org.apache.commons.lang3.StringUtils;
import java.util.*;

/** 只投影可读问题与选项，不向平台暴露原始事件、工作目录或内部调用参数。 */
final class ImBotInteraction {
    private ImBotInteraction(){ }

    /** 按问题标识合并状态，返回尚未解决的问题；已回答及拒绝事件会覆盖旧事件。 */
    static JSONObject pending(List<AgentEvent> events) {
        Map<String,JSONObject> latest=new LinkedHashMap<>();
        for(AgentEvent event:events) if(AgentEventType.QUESTION_REQUEST.equals(event.getType()) && event.getPayload()!=null) {
            JSONObject payload=JSONUtil.parseObj(event.getPayload());String id=payload.getStr("requestId");
            if(StringUtils.isNotBlank(id))latest.put(id,payload);
        }
        for(JSONObject value:latest.values()) if(QuestionStatus.PENDING.getCode().equals(value.getStr("status"))
                && value.getJSONArray("questions")!=null && !value.getJSONArray("questions").isEmpty())return value;
        return null;
    }

    /** 返回当前一道题，不复制上游 payload 中的目录、原始属性等内部字段。 */
    static JSONObject current(JSONObject pending,JSONObject state) {
        JSONArray answers=state.getJSONArray("answers"),questions=pending.getJSONArray("questions");
        int index=answers==null?0:answers.size();
        return index<questions.size()?questions.getJSONObject(index):null;
    }

    /** 将一道题显示为编号选项，多题每次只展示尚未回答的第一题。 */
    static String prompt(JSONObject pending,JSONObject state) {
        return prompt(pending,state,false);
    }
    /** 真人会话只展示问题本身，不发送内部提交状态或工作台提示。 */
    static String userPrompt(JSONObject pending,JSONObject state){return prompt(pending,state,true);}
    private static String prompt(JSONObject pending,JSONObject state,boolean user) {
        JSONObject question=current(pending,state);
        if(question==null)return user?"":QuestionSubmission.UNKNOWN.getCode().equals(state.getStr("submission"))
                ?"答案已记录，提交结果暂时无法确认，请在工作台核查原问题；系统不会重复提交。":"答案已提交，正在等待原运行继续。";
        int index=state.getJSONArray("answers").size();
        StringBuilder text=new StringBuilder();
        if(!user)text.append("**需要补充信息（").append(index+1).append('/').append(pending.getJSONArray("questions").size()).append("）**\n");
        text.append(StringUtils.defaultIfBlank(question.getStr("question"),question.getStr("header"))).append('\n');
        JSONArray options=question.getJSONArray("options");
        if(options!=null)for(int i=0;i<options.size();i++) {
            JSONObject option=options.getJSONObject(i);text.append('\n').append(i+1).append(". ").append(option.getStr("label"));
            if(StringUtils.isNotBlank(option.getStr("description")))text.append("：").append(option.getStr("description"));
        }
        text.append(question.getBool("multiple",false)?"\n\n可多选，回复编号并用逗号分隔，也可直接输入答案。":"\n\n回复编号或直接输入答案。" );
        return text.toString();
    }

    /** 编号映射为原始选项文本；自定义文字原样传给已有问题回复接口。 */
    static List<String> answer(JSONObject question,String text) {
        String value=StringUtils.trimToEmpty(text);
        if(value.isEmpty())throw BizException.paramError("答案不能为空");
        JSONArray options=question.getJSONArray("options");
        if(options!=null && !options.isEmpty() && value.matches("[0-9]+(?:[，,、\\s]+[0-9]+)*")) {
            String[] indexes=value.split("[，,、\\s]+");
            if(!question.getBool("multiple",false) && indexes.length>1)throw BizException.paramError("本题只能选择一个选项");
            List<String> answers=new ArrayList<>();
            for(String index:indexes) {
                int n;
                try{n=Integer.parseInt(index);}catch(NumberFormatException e){throw BizException.paramError("选项编号超出范围，请重新输入");}
                if(options==null || n<1 || n>options.size())throw BizException.paramError("选项编号超出范围，请重新输入");
                String label=options.getJSONObject(n-1).getStr("label");
                if(!answers.contains(label))answers.add(label);
            }
            return answers;
        }
        return Collections.singletonList(value);
    }

    /** 为三类可读内容分配保守字节预算，避免长思考覆盖正文或待答问题。 */
    static String render(String reasoning,String answer,String prompt,String sessionId) {
        StringBuilder result=new StringBuilder();
        if(StringUtils.isNotBlank(reasoning))result.append("**思考**\n").append(ImBotContent.abbreviate(reasoning,900)).append("\n\n");
        if(StringUtils.isNotBlank(answer))result.append("**回复**\n").append(ImBotContent.abbreviate(answer,2200)).append("\n\n");
        if(StringUtils.isNotBlank(prompt))result.append(ImBotContent.abbreviate(prompt,750)).append('\n');
        if(result.length()==0)result.append("正在处理，请稍候。");
        if(reasoning.codePointCount(0,reasoning.length())>900 || answer.codePointCount(0,answer.length())>2200 || prompt.codePointCount(0,prompt.length())>750)
            result.append("\n内容较长，请在工作台会话 ").append(sessionId).append(" 查看完整内容。");
        return ImBotContent.answer(result.toString().trim(),sessionId);
    }
}
