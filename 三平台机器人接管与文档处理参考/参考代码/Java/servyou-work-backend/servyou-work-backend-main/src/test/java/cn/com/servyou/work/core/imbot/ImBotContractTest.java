package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.web.controller.ImBotController;
import cn.com.servyou.work.web.controller.ImBotDocumentController;
import cn.com.servyou.work.web.controller.ImBotToolController;
import cn.com.servyou.work.web.controller.admin.AdminImBotController;
import cn.com.servyou.work.web.controller.ImBotTakeoverController;
import cn.com.servyou.work.web.controller.admin.AdminImBotTakeoverController;
import cn.com.servyou.work.web.converter.ImBotWebConverter;
import cn.com.servyou.work.web.request.imbot.*;
import cn.com.servyou.work.web.response.imbot.*;
import cn.com.servyou.work.dao.mapper.*;
import cn.hutool.core.io.IoUtil;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import java.lang.reflect.*;
import java.io.InputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImBotContractTest {
    @Test void takeoverIdentityMappingPassesThroughWebConverterWithStringVersions(){
        ReqImBotTakeoverVO request=new ReqImBotTakeoverVO();request.setBotKey("1");request.setExpectedConfigVersion("9007199254740993");
        request.setExternalUserId("callback-self");request.setMessageUserId("mcp-self");
        cn.com.servyou.work.api.model.imbot.ImBotTakeoverRequestDTO command=ImBotWebConverter.toDTO(request);
        assertEquals("callback-self",command.getExternalUserId());assertEquals("mcp-self",command.getMessageUserId());
        cn.com.servyou.work.api.model.imbot.ImBotTakeoverDTO dto=new cn.com.servyou.work.api.model.imbot.ImBotTakeoverDTO();
        dto.setBotKey(command.getBotKey());dto.setConfigVersion(command.getExpectedConfigVersion());
        dto.setExternalUserId(command.getExternalUserId());dto.setMessageUserId(command.getMessageUserId());
        RespImBotTakeoverVO response=ImBotWebConverter.toVO(dto);
        assertEquals("9007199254740993",response.getConfigVersion());assertEquals("callback-self",response.getExternalUserId());assertEquals("mcp-self",response.getMessageUserId());
    }
    @Test void writeOnlyAuthorizationUrlsCanBeReceivedButNeverSerialized()throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        ReqImBotSaveVO request=mapper.readValue("{\"botKey\":\"1\",\"expectedConfigVersion\":\"1\",\"documentMcpUrl\":\"test-only-url\",\"mcpUrls\":{\"todo\":\"test-only-secret\"}}",ReqImBotSaveVO.class);
        assertEquals("test-only-url",request.getDocumentMcpUrl());assertEquals("test-only-secret",request.getMcpUrls().get("todo"));
        assertEquals(request.getMcpUrls(),ImBotWebConverter.toDTO(request).getMcpUrls());
        assertFalse(mapper.writeValueAsString(request).contains("test-only"));
        assertFalse(cn.hutool.json.JSONUtil.toJsonStr(request).contains("test-only"));
    }
    @Test void businessScannerUsesTenSecondInterval()throws Exception {
        assertEquals(10000,ImBotRuntime.class.getMethod("dispatch").getAnnotation(Scheduled.class).fixedDelay());
    }
    @Test void fixedGetPostRoutesAndStringBusinessIdentifiersRemainConsistent() {
        int count=0;
        for(Class<?> type:Arrays.asList(ImBotController.class,AdminImBotController.class,ImBotDocumentController.class,ImBotToolController.class,ImBotTakeoverController.class,AdminImBotTakeoverController.class)) for(Method method:type.getDeclaredMethods()) {
            GetMapping get=method.getAnnotation(GetMapping.class);PostMapping post=method.getAnnotation(PostMapping.class);
            if(get==null && post==null)continue;count++;
            for(String path:get==null?post.value():get.value()) assertFalse(path.contains("{"));
            for(Parameter parameter:method.getParameters()) {
                assertNull(parameter.getAnnotation(PathVariable.class));
                if(get!=null){assertNotNull(parameter.getAnnotation(RequestParam.class));assertEquals(String.class,parameter.getType());}
                else if(type==ImBotDocumentController.class || type==ImBotToolController.class)assertNotNull(parameter.getAnnotation(RequestBody.class));
            }
        }
        assertEquals(32,count);
        for(Class<?> type:Arrays.asList(ReqImBotQueryVO.class,ReqImBotSaveVO.class,ReqImBotOperationVO.class,ReqImBotSendVO.class,ReqImBotDocumentVO.class,ReqImBotToolVO.class,RespImBotVO.class,RespImBotSessionVO.class,RespImBotMessageVO.class,ReqImBotTakeoverVO.class,ReqImBotTakeoverOAuthVO.class,RespImBotTakeoverVO.class))
            for(Field field:type.getDeclaredFields()) if(field.getName().matches(".*(Id|Key|Version)$"))assertEquals(String.class,field.getType(),type.getSimpleName()+"."+field.getName());
        for(Class<?> type:Arrays.asList(RespImBotVO.class,RespImBotMessageVO.class,RespImBotUserVO.class))
            for(Field field:type.getDeclaredFields())assertFalse(field.getName().toLowerCase(Locale.ROOT).contains("secret" ) && !field.getName().equals("hasSecret"));
        ReqImBotSendVO invalid=new ReqImBotSendVO();invalid.setBotKey("9223372036854775808");assertThrows(BizException.class,()->ImBotWebConverter.toDTO(invalid));
        assertThrows(NoSuchFieldException.class,()->ReqImBotSaveVO.class.getDeclaredField("defaultAgentId"));
        assertThrows(NoSuchFieldException.class,()->RespImBotVO.class.getDeclaredField("defaultAgentId"));
    }
    @Test void mapperCodeBoundaryAndEnumCoverage()throws Exception {
        for(String name:Arrays.asList("ImBotMapper","ImBotMessageMapper","ImBotSessionMapper"))try(InputStream in=getClass().getResourceAsStream("/mapper/"+name+".xml")) {
            assertNotNull(in);String xml=IoUtil.readUtf8(in);assertFalse(xml.contains("Enum@"));assertFalse(xml.contains("ImBotEnums"));
        }
        for(Class<?> type:Arrays.asList(ImBotMapper.class,ImBotMessageMapper.class,ImBotUserMapper.class,ImBotSessionMapper.class))
            for(Method method:type.getDeclaredMethods())for(Class<?> parameter:method.getParameterTypes())assertFalse(parameter.isEnum());
        for(Class<?> type:ImBotEnums.class.getDeclaredClasses())if(type.isEnum()) {
            Set<String> codes=new HashSet<>();for(Object value:type.getEnumConstants())assertTrue(codes.add(((ImBotEnums.Code)value).getCode()));
        }
    }
    @Test void contentLimitsCountUtf8AndPreserveUnicode() {
        char[] chars=new char[6000];Arrays.fill(chars,'中');String large=new String(chars);
        assertThrows(BizException.class,()->ImBotContent.validate(ImBotEnums.Platform.FEISHU.getCode(),ImBotEnums.Content.TEXT.getCode(),large));
        assertThrows(BizException.class,()->ImBotContent.validate(ImBotEnums.Platform.FEISHU.getCode(),ImBotEnums.Content.MARKDOWN.getCode(),"内容"));
        String truncated=ImBotContent.answer(large,"session-1");assertTrue(truncated.contains("session-1"));assertTrue(truncated.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=16000);
    }
}
