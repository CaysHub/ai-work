package cn.com.servyou.work.core.imbot;

import cn.hutool.json.*;
import cn.com.servyou.work.api.enums.ImBotEnums.Platform;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.dao.entity.ImBotEntity;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 无平台消息ID时的去重边界及人工回复先后关系。 */
class ImBotTakeoverTest {
    @Test void wecomSuccessfulEmptyResponsesMayOmitMessageAndChatArrays(){
        for(String field:Arrays.asList("messages","chats")){
            JSONObject empty=JSONUtil.createObj().set("errcode",0).set(field+"_count",0).set("has_more",false);
            assertTrue(ImBotUserGateway.wecomItems(empty,field).isEmpty());
        }
    }
    @Test void wecomMissingListCannotSilentlyDiscardMessagesOrAnUnfinishedPage(){
        JSONObject data=JSONUtil.createObj().set("errcode",0).set("messages_count",1).set("has_more",false);
        assertThrows(BizException.class,()->ImBotUserGateway.wecomItems(data,"messages"));
        data.set("messages_count",0).set("has_more",true);
        assertThrows(BizException.class,()->ImBotUserGateway.wecomItems(data,"messages"));
        data.remove("has_more");assertThrows(BizException.class,()->ImBotUserGateway.wecomItems(data,"messages"));
        data.set("has_more",false);data.remove("messages_count");
        assertThrows(BizException.class,()->ImBotUserGateway.wecomItems(data,"messages"));
        data.set("messages_count",0).set("errcode",40001);
        assertThrows(BizException.class,()->ImBotUserGateway.wecomItems(data,"messages"));
    }
    @Test void wecomMessageIdentityIsSeparateAndCannotFallBackToCallbackIdentity(){
        ImBotEntity bot=new ImBotEntity();bot.setPlatform(Platform.WECOM.getCode());
        JSONObject config=JSONUtil.createObj().set("externalUserId","callback-self");
        assertThrows(BizException.class,()->ImBotTakeoverConfig.messageUserId(bot,config));
        config.set("messageUserId","mcp-self");assertEquals("mcp-self",ImBotTakeoverConfig.messageUserId(bot,config));
        assertEquals("callback-self",config.getStr("externalUserId"));
    }
    @Test void feishuMessageIdentityAlwaysUsesVerifiedOpenId(){
        ImBotEntity bot=new ImBotEntity();bot.setPlatform(Platform.FEISHU.getCode());
        JSONObject config=JSONUtil.createObj().set("externalUserId","verified-open-id").set("messageUserId","different-user");
        assertEquals("verified-open-id",ImBotTakeoverConfig.messageUserId(bot,config));
    }
    @Test void feishuUserAuthorizationRequestsBothOfficialSendPermissions(){
        List<String> scopes=Arrays.asList(ImBotTakeoverServiceImpl.feishuAuthorizationScopes(false).split(" "));
        assertTrue(scopes.contains("im:message"));assertTrue(scopes.contains("im:message.send_as_user"));
        assertTrue(scopes.contains("offline_access"));assertTrue(scopes.contains("im:message.p2p_msg:get_as_user"));
        assertTrue(scopes.contains("im:message.group_msg:get_as_user"));
        assertFalse(scopes.contains("im:message:send_as_user"));assertFalse(scopes.contains("search:message"));
        assertEquals(scopes.size(),new HashSet<>(scopes).size());
    }
    @Test void feishuSearchPermissionIsRequestedOnlyForDiscovery(){
        Set<String> ordinary=new HashSet<>(Arrays.asList(ImBotTakeoverServiceImpl.feishuAuthorizationScopes(false).split(" ")));
        Set<String> discovery=new HashSet<>(Arrays.asList(ImBotTakeoverServiceImpl.feishuAuthorizationScopes(true).split(" ")));
        discovery.removeAll(ordinary);assertEquals(Collections.singleton("search:message"),discovery);
    }
    @Test void dingTalkDeviceGrantRequiresConfirmedCode() {
        JSONObject pending = JSONUtil.createObj().set("error","authorization_pending");
        assertThrows(BizException.class, () -> DingTalkUserAuthorization.requireAuthorizationCode(pending));
        JSONObject denied = JSONUtil.createObj().set("status","REJECTED").set("authCode","unusable-code");
        assertThrows(BizException.class, () -> DingTalkUserAuthorization.requireAuthorizationCode(denied));
        JSONObject approved = JSONUtil.createObj().set("authCode","confirmed-code");
        assertEquals("confirmed-code", DingTalkUserAuthorization.requireAuthorizationCode(approved));
    }
    @Test void dingTalkConversationAndHistoryUseDifferentTimestampFormats() {
        long history = DingTalkUserMessages.timestamp("2026-11-20 09:00:38");
        long conversation = DingTalkUserMessages.timestamp("2026-11-20T09:00:38.618+08:00");
        assertEquals(history + 618, conversation);
    }
    @Test void dingTalkHistoryRejectsAnUnverifiedConversationAndKeepsOwnerIdentity() {
        JSONObject item = JSONUtil.createObj().set("openMessageId","m1").set("senderOpenDingTalkId","owner-open-id")
                .set("openConversationId","chat-a").set("createTime","2026-11-20 09:00:38")
                .set("sendType","user").set("content","已人工回复");
        JSONObject response = JSONUtil.createObj().set("hasMore",false).set("messages",new JSONArray().put(item));
        long time = DingTalkUserMessages.timestamp(item.getStr("createTime"));
        ImBotUserGateway.Page result = DingTalkUserMessages.historyPage(response,"chat-a",time,time,String.valueOf(time));
        assertEquals("owner-open-id",result.getItems().getJSONObject(0).getStr("sender"));
        assertThrows(BizException.class, () -> DingTalkUserMessages.historyPage(response,"chat-b",time,time,String.valueOf(time)));
    }
    @Test void sharedGroupUsesPlatformMentionsAndExactConfiguredNames() {
        java.util.regex.Pattern names=ImBotGroupMentions.names(Arrays.asList("测试员工甲","examplePeer","examplePeer 二组"));
        JSONObject plain=JSONUtil.createObj().set("text","请 @测试员工甲 帮忙，@examplePeer 二组 看下");
        assertTrue(ImBotGroupMentions.addressed(plain,"chai","测试员工甲",names));
        assertTrue(ImBotGroupMentions.addressed(plain,"group-two","examplePeer 二组",names));
        assertFalse(ImBotGroupMentions.addressed(plain,"tiger","examplePeer",names));
        assertFalse(ImBotGroupMentions.addressed(plain.set("text","邮件是 name@examplePeer"),"tiger","examplePeer",names));
        assertFalse(ImBotGroupMentions.addressed(plain.set("text","@所有人"),"tiger","examplePeer",names));
        JSONObject dingTalk=JSONUtil.createObj().set("text","<@D-user> 请回答");
        assertTrue(ImBotGroupMentions.addressed(dingTalk,"D-user",null,null));
        assertFalse(ImBotGroupMentions.addressed(dingTalk,"D-other",null,null));
        JSONArray platform=ImBotGroupMentions.feishuIds(new JSONArray()
                .put(JSONUtil.createObj().set("id","ou_verified").set("id_type","open_id"))
                .put(JSONUtil.createObj().set("id","other-id-domain").set("id_type","user_id")));
        JSONObject feishu=JSONUtil.createObj().set("text","@examplePeer").set("mentionedUserIds",platform);
        assertEquals(Collections.singletonList("ou_verified"),platform.toList(String.class));
        assertTrue(ImBotGroupMentions.addressed(feishu,"ou_verified",null,names));
        assertFalse(ImBotGroupMentions.addressed(feishu,"ou_other","examplePeer",names));
    }
    @Test void dingTalkOwnerRequiresUnambiguousStaffIdentityInTheAuthorizedOrganization() {
        JSONObject employee = JSONUtil.createObj().set("corpId","corp-a").set("userId","bound-user");
        JSONArray records = new JSONArray().put(JSONUtil.createObj().set("orgEmployeeModel",employee));
        JSONObject profile = JSONUtil.createObj().set("result",records);
        assertTrue(DingTalkUserAuthorization.matchesOwner(profile,"corp-a","bound-user"));
        assertFalse(DingTalkUserAuthorization.matchesOwner(profile,"corp-b","bound-user"));
        assertFalse(DingTalkUserAuthorization.matchesOwner(profile,"corp-a","other-user"));
        records.add(JSONUtil.createObj().set("orgEmployeeModel",employee));
        assertFalse(DingTalkUserAuthorization.matchesOwner(profile,"corp-a","bound-user"));
    }
    @Test void identicalWecomMessagesInOneSecondAreDistinctAndOverlapIsStable(){
        JSONObject row=JSONUtil.createObj().set("sender","user").set("time",123000L).set("type","text").set("text","同秒重复");
        JSONArray window=new JSONArray();window.add(row);window.add(JSONUtil.parseObj(row.toString()));
        List<JSONObject> first=ImBotTakeover.identify("chat",window),second=ImBotTakeover.identify("chat",window);
        assertNotEquals(first.get(0).getStr("id"),first.get(1).getStr("id"));
        assertEquals(first.get(0).getStr("id"),second.get(0).getStr("id"));assertEquals(first.get(1).getStr("id"),second.get(1).getStr("id"));
        assertNotEquals(first.get(0).getStr("id"),ImBotTakeover.identify("other-chat",window).get(0).getStr("id"));
    }
    @Test void manualReplyOrderingIsConservativeOnlyForSecondPrecision(){
        JSONObject source=JSONUtil.createObj().set("sourceTime",1000L).set("sourcePosition",2);
        JSONObject same=JSONUtil.createObj().set("time",1000L).set("position",1);
        assertTrue(ImBotTakeover.after(same,source,true));assertFalse(ImBotTakeover.after(same,source,false));
        same.set("position",3);assertTrue(ImBotTakeover.after(same,source,false));same.set("time",999L);assertFalse(ImBotTakeover.after(same,source,true));
    }
    @Test void overlappingWindowsKeepContextWithoutInventingDuplicateMessages(){
        JSONObject first=JSONUtil.createObj().set("sender","other").set("time",1000L).set("type","text").set("text","之前的问题");
        JSONArray raw=new JSONArray();raw.add(first);JSONArray history=ImBotTakeover.combineHistory("chat",null,raw);
        raw.add(JSONUtil.createObj().set("sender","self").set("time",2000L).set("type","text").set("text","人工回复"));
        JSONArray combined=ImBotTakeover.combineHistory("chat",history,raw);assertEquals(2,combined.size());
        JSONArray followup=new JSONArray();followup.add(JSONUtil.createObj().set("sender","other").set("time",900000L).set("type","text").set("text","后续追问"));
        combined=ImBotTakeover.combineHistory("chat",combined,followup);assertEquals(3,combined.size());assertEquals("人工回复",combined.getJSONObject(1).getStr("text"));
    }
    @Test void userQuestionContainsOnlyQuestionAndOptionsWithoutSubmissionStatus(){
        JSONObject pending=JSONUtil.createObj().set("questions",new JSONArray().put(JSONUtil.createObj().set("question","哪个城市？")));
        JSONObject state=JSONUtil.createObj().set("answers",new JSONArray());
        assertTrue(ImBotInteraction.userPrompt(pending,state).startsWith("哪个城市？"));
        state.getJSONArray("answers").add(Collections.singletonList("杭州"));state.set("submission","UNKNOWN");
        assertEquals("",ImBotInteraction.userPrompt(pending,state));assertTrue(ImBotInteraction.prompt(pending,state).contains("工作台"));
    }
}
