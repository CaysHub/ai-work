package cn.com.servyou.work.core.imbot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.enums.ImBotEnums;
import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.enums.ImBotMcpCapability;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.api.interfaces.imbot.IImBotToolService;
import cn.com.servyou.work.api.interfaces.conversation.IConversationService;
import cn.com.servyou.work.api.model.document.FileDownloadDTO;
import cn.com.servyou.work.api.model.conversation.ConversationFileUploadDTO;
import cn.com.servyou.work.api.model.conversation.ReqConversationFileUploadDTO;
import cn.com.servyou.work.api.enums.UploadTypeEnum;
import cn.com.servyou.work.api.model.imbot.*;
import cn.com.servyou.work.core.impl.conversation.ConversationFileOwnershipService;
import cn.com.servyou.work.core.manager.storage.StorageManager;
import cn.com.servyou.work.core.utils.UserInfoUtil;
import cn.com.servyou.work.dao.entity.ImBotEntity;
import cn.hutool.core.codec.Base64;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONArray;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;
import static cn.com.servyou.work.core.utils.ExceptionLogUtil.safeStackTrace;

/** 七类官方能力共用的权限、配置、目录和调用服务，不注册重复的后台任务。 */
@Service
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class ImBotToolServiceImpl implements IImBotToolService {
    /** 复用机器人租户、用户授权、绑定和启用校验。 */
    private final ImBotServiceImpl bots;
    /** 读取既有主密钥环境。 */
    private final Environment environment;
    /** 复用同一MCP短时会话客户端。 */
    private final ImBotWecomDocuments client;
    /** 校验当前用户的会话文件读取权限。 */
    private final IConversationService conversations;
    /** 读取既有对象存储的文件字节。 */
    private final StorageManager storage;
    /** 下载前复用既有会话归属检查。 */
    private final ConversationFileOwnershipService fileOwnership;
    /** 只在会话归属校验时开启短事务，不跨平台HTTP持有事务。 */
    private final TransactionTemplate transaction;

    @Override public IPage<ImBotToolDTO> page(ImBotToolRequestDTO request) {
        try {
            String endpoint=endpoint(request);
            int number=request.getPageNum()==null?1:request.getPageNum();
            int size=request.getPageSize()==null?5:request.getPageSize();
            if(number<1 || number>100000 || size<1 || size>20)throw BizException.paramError("工具分页pageNum须为1至100000，pageSize须为1至20");
            if(StringUtils.length(request.getKeyword())>128)throw BizException.paramError("工具查询关键词过长");
            List<ImBotToolDTO> selected=new ArrayList<>();
            for(Map<String,Object> tool:client.tools(endpoint)) {
                String name=String.valueOf(tool.get("name"));String description=String.valueOf(tool.getOrDefault("description",""));
                if(StringUtils.isNotBlank(request.getTool()) && !request.getTool().equals(name))continue;
                if(StringUtils.isNotBlank(request.getKeyword()) && !StringUtils.containsIgnoreCase(name,request.getKeyword())
                        && !StringUtils.containsIgnoreCase(description,request.getKeyword()))continue;
                ImBotToolDTO dto=new ImBotToolDTO();dto.setCapability(request.getCapability());dto.setTool(name);dto.setDescription(description);
                dto.setInputSchema(JSONUtil.parseObj(tool.get("inputSchema")));selected.add(dto);
            }
            int from=(int)Math.min((long)(number-1)*size,selected.size());int to=Math.min(from+size,selected.size());
            return new Page<ImBotToolDTO>(number,size).setTotal(selected.size()).setRecords(new ArrayList<>(selected.subList(from,to)));
        } catch(Exception failure) { throw failure(request,failure,false); }
    }

    @Override public Map<String,Object> execute(ImBotToolRequestDTO request) {
        try {
            String endpoint=endpoint(request);
            Map<String,Object> arguments=request.getArguments()==null?new LinkedHashMap<>():new LinkedHashMap<>(request.getArguments());
            if(JSONUtil.toJsonStr(arguments).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024*1024)throw BizException.paramError("单次工具业务参数不能超过1MB，请分段处理");
            ImBotWecomDocuments.rejectLocalPaths(arguments);
            if(request.getConversationFileId()!=null)uploadArguments(request,arguments);
            boolean download="media_download".equals(request.getTool());
            if(download) {
                if(StringUtils.isBlank(request.getSessionId()))throw BizException.paramError("媒体下载须指定本人sessionId，文件由后端保存到该会话");
                transaction.execute(status->fileOwnership.requireUpload(UserInfoUtil.getTenantId(),UserInfoUtil.getUserId(),request.getSessionId()));
            }
            Map<String,Object> result=client.call(endpoint,request.getTool(),arguments);
            if(download)saveDownloadedMedia(request,result);
            log.info("机器人平台工具完成 botKey={}, capability={}, tool={}, userId={}, tenantId={}",request.getBotKey(),request.getCapability(),request.getTool(),UserInfoUtil.getUserId(),UserInfoUtil.getTenantId());
            return result;
        } catch(Exception failure) { throw failure(request,failure,true); }
    }

    /** 平台媒体内容沿用会话上传链路；不向技能返回大段Base64或平台本地路径。 */
    private void saveDownloadedMedia(ImBotToolRequestDTO request,Map<String,Object> result)throws Exception {
        List<JSONObject> payloads=new ArrayList<>();
        Object structured=result.get("structuredContent");
        if(structured instanceof Map)payloads.add(JSONUtil.parseObj(structured));
        JSONArray content=JSONUtil.parseObj(result).getJSONArray("content");
        if(content!=null)for(int i=0;i<content.size();i++) {
            JSONObject block=content.getJSONObject(i);
            if("text".equals(block.getStr("type")) && JSONUtil.isTypeJSONObject(block.getStr("text")))payloads.add(JSONUtil.parseObj(block.getStr("text")));
        }
        JSONObject media=null;
        for(JSONObject payload:payloads)if(StringUtils.isNotBlank(payload.getStr("base64_content"))){media=payload;break;}
        if(media==null)throw new BizException(Error.FILE_FAILED.getCode(),"平台未返回可保存的媒体内容，请核对媒体ID和任务状态");
        byte[] bytes=java.util.Base64.getDecoder().decode(media.getStr("base64_content"));
        try(ImBotFile file=ImBotFile.copyOf(new ByteArrayInputStream(bytes),media.getStr("file_name"),media.getStr("content_type"),20L*1024*1024)) {
            ReqConversationFileUploadDTO upload=new ReqConversationFileUploadDTO();upload.setSessionId(request.getSessionId());upload.setUploadType(UploadTypeEnum.SESSION.getCode());
            ConversationFileUploadDTO saved=conversations.uploadFile(upload,file);
            result.put("file",JSONUtil.createObj().set("fileId",String.valueOf(saved.getFileId())).set("conversationFileId",String.valueOf(saved.getConversationFileId()))
                    .set("sessionId",request.getSessionId()).set("fileName",saved.getFileName()).set("fileSize",saved.getFileSize()));
        }
        if(structured instanceof Map)((Map<?,?>)structured).remove("base64_content");
        if(content!=null) {
            for(int i=0;i<content.size();i++) {
                JSONObject block=content.getJSONObject(i);
                if("text".equals(block.getStr("type")) && JSONUtil.isTypeJSONObject(block.getStr("text"))) {
                    JSONObject payload=JSONUtil.parseObj(block.getStr("text"));payload.remove("base64_content");block.set("text",payload.toString());
                }
            }
            result.put("content",content);
        }
    }

    private String endpoint(ImBotToolRequestDTO request) {
        if(request==null)throw BizException.paramError("工具请求必填");
        ImBotMcpCapability capability=ImBotMcpCapability.fromCode(request.getCapability());
        ImBotEntity bot=bots.requireDocumentBot(request.getBotKey());
        if(!ImBotEnums.Platform.WECOM.getCode().equals(bot.getPlatform()))throw BizException.paramError("此工具入口仅适用于企业微信机器人，飞书沿用文档接口");
        String endpoint=bots.toolMcpUrls(bot).get(capability.getCode());
        if(StringUtils.isBlank(endpoint))throw new BizException(Error.TOOL_NOT_CONFIGURED.getCode(),"请先配置企微"+capability.getCode()+"能力的机器人授权地址");
        ImBotMcpConfig.requireEndpoint(capability,endpoint);return endpoint;
    }

    /** 会话文件经后端校验后转为平台明确支持的Base64上传，不暴露存储路径。 */
    private void uploadArguments(ImBotToolRequestDTO request,Map<String,Object> arguments)throws Exception {
        if(!"media_upload".equals(request.getTool()) || StringUtils.isBlank(request.getSessionId()))throw BizException.paramError("会话文件上传须使用media_upload并传本人sessionId");
        if(arguments.containsKey("base64_content") || arguments.containsKey("file_name"))throw BizException.paramError("会话文件上传的文件名和内容由后端读取，请勿重复传入");
        FileDownloadDTO file=conversations.getConversationDownloadFile(request.getSessionId(),request.getConversationFileId());
        try(InputStream input=storage.getObject(file.getBucketName(),file.getObjectKey());
            ImBotFile local=ImBotFile.copyOf(input,file.getFileName(),"application/octet-stream",20L*1024*1024)) {
            arguments.put("file_name",local.getOriginalFilename());arguments.put("base64_content",Base64.encode(local.getBytes()));
            arguments.putIfAbsent("type",ImBotEnums.Wire.FILE.getCode());
        }
    }

    private BizException failure(ImBotToolRequestDTO request,Exception failure,boolean execute) {
        log.warn("机器人平台工具失败 botKey={}, capability={}, tool={}, userId={}, tenantId={}\n{}",request==null?null:request.getBotKey(),
                request==null?null:request.getCapability(),request==null?null:request.getTool(),UserInfoUtil.getUserId(),UserInfoUtil.getTenantId(),safeStackTrace(failure));
        if(failure instanceof BizException)return (BizException)failure;
        return new BizException(execute?Error.TOOL_RESULT_UNKNOWN.getCode():Error.TOOL_FAILED.getCode(),
                execute?"平台工具结果未确认，请核查原资源或任务，禁止自动重复发送、创建或修改":"企微工具目录读取失败，请检查该能力授权",failure);
    }
}
