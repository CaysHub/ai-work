package cn.com.servyou.work.core.imbot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.enums.*;
import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.api.interfaces.imbot.IImBotDocumentService;
import cn.com.servyou.work.api.interfaces.conversation.IConversationService;
import cn.com.servyou.work.api.model.imbot.*;
import cn.com.servyou.work.api.model.conversation.*;
import cn.com.servyou.work.api.model.document.FileDownloadDTO;
import cn.com.servyou.work.core.manager.storage.StorageManager;
import cn.com.servyou.work.core.utils.*;
import cn.com.servyou.work.dao.entity.ImBotEntity;
import cn.hutool.json.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.InputStream;
import java.util.*;
import static cn.com.servyou.work.api.enums.ImBotDocumentAction.*;
import static cn.com.servyou.work.core.utils.ExceptionLogUtil.safeStackTrace;

/** 技能调用的文档业务适配；平台身份固定为指定机器人，入口身份仍为当前授权用户。 */
@Service
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class ImBotDocumentServiceImpl implements IImBotDocumentService {
    /** 复用机器人租户、启用和用户绑定权限。 */
    private final ImBotServiceImpl bots;
    /** 复用项目统一密钥环境。 */
    private final Environment environment;
    /** 复用已有企微 MCP 协议组件。 */
    private final ImBotWecomDocuments wecom;
    /** 复用当前用户会话文件上传及下载权限。 */
    private final IConversationService conversations;
    /** 复用统一对象存储。 */
    private final StorageManager storage;
    /** 文件请求在平台下载前核验当前用户会话归属。 */
    private final cn.com.servyou.work.core.impl.conversation.ConversationFileOwnershipService fileOwnership;
    /** 会话归属校验使用既有短事务，不跨平台下载持有事务。 */
    private final TransactionTemplate transaction;

    @Override public ImBotDocumentDTO capabilities(Long botKey) {
        ImBotEntity bot=bots.requireDocumentBot(botKey);
        ImBotDocumentDTO result=view(bot);
        if(ImBotEnums.Platform.WECOM.getCode().equals(bot.getPlatform()) && Boolean.TRUE.equals(result.getConfigured())) {
            try {
                Map<String,Object> schemas=new LinkedHashMap<>();List<String> actions=new ArrayList<>();
                for(Map<String,Object> tool:wecom.tools(documentEndpoint(bot))) {
                    String code="wecom."+tool.get("name");actions.add(code);schemas.put(code,tool.get("inputSchema"));
                }
                result.setActions(actions);result.setData(schemas);
            } catch(Exception failure) {
                log.warn("企微文档工具发现失败 botKey={}\n{}",botKey,safeStackTrace(failure));
                if(failure instanceof BizException)throw (BizException)failure;
                throw new BizException(Error.TOOL_FAILED.getCode(),"企微文档工具目录查询失败",failure);
            }
        }
        return result;
    }
    private ImBotDocumentDTO view(ImBotEntity bot) {
        ImBotDocumentDTO result=new ImBotDocumentDTO();result.setPlatform(bot.getPlatform());
        if (ImBotEnums.Platform.DINGTALK.getCode().equals(bot.getPlatform())) {
            result.setConfigured(false);
            result.setActions(Collections.emptyList());
            result.setNotice("钉钉支持机器人消息，云文档能力尚未接入");
            return result;
        }
        boolean feishu=ImBotEnums.Platform.FEISHU.getCode().equals(bot.getPlatform());
        result.setConfigured(feishu || bots.toolMcpUrls(bot).containsKey(ImBotMcpCapability.DOCUMENT.getCode()));
        result.setNotice(feishu?"使用机器人应用身份；需同时开通对应 API 权限并将目标文档授权给应用，不使用个人 OAuth。"
                :"使用机器人文档授权；v2能力和参数以实时目录为准，资源范围包含授权人允许访问的资源。建议通过tool/page按需获取定义，避免一次加载全部文档Schema。");
        List<String> codes=new ArrayList<>();Map<String,Object> descriptions=new LinkedHashMap<>();
        for(ImBotDocumentAction action:values())if(feishu && action.getPlatform().getCode().equals(bot.getPlatform())) {
            codes.add(action.getCode());Map<String,Object> schema=new LinkedHashMap<>();
            schema.put("requiredResourceFields",action.getPathFields());schema.put("queryFields",action.getQueryFields());
            schema.put("bodyFields",action.getBodyFields());descriptions.put(action.getCode(),schema);
        }
        result.setActions(codes);result.setData(descriptions);return result;
    }
    @Override public ImBotDocumentDTO execute(ImBotDocumentRequestDTO request) {
        ImBotDocumentAction action=null;
        try {
            if(request==null)throw BizException.paramError("文档请求必填");
            ImBotEntity bot=bots.requireDocumentBot(request.getBotKey());
            if(ImBotEnums.Platform.WECOM.getCode().equals(bot.getPlatform()))return executeWecom(bot,request);
            action=fromCode(request.getAction());
            if(!action.getPlatform().getCode().equals(bot.getPlatform()))throw BizException.paramError("文档操作与机器人平台不一致");
            Map<String,Object> arguments=request.getArguments()==null?Collections.emptyMap():request.getArguments();
            validate(action,arguments);
            Map<String,Object> data=feishu(bot,action,arguments,request);
            ImBotDocumentDTO result=new ImBotDocumentDTO();result.setPlatform(bot.getPlatform());result.setConfigured(true);result.setData(data);
            log.info("机器人文档操作完成 botKey={}, platform={}, action={}, userId={}, tenantId={}",bot.getId(),bot.getPlatform(),action.getCode(),UserInfoUtil.getUserId(),UserInfoUtil.getTenantId());
            return result;
        } catch(Exception failure) {
            log.warn("机器人文档操作失败 botKey={}, action={}, userId={}, tenantId={}\n{}",request==null?null:request.getBotKey(),
                    action==null?null:action.getCode(),UserInfoUtil.getUserId(),UserInfoUtil.getTenantId(),safeStackTrace(failure));
            if(failure instanceof BizException)throw (BizException)failure;
            // 不对写入进行自动重放；网络异常时平台可能已经完成操作。
            throw new BizException(Error.DOCUMENT_RESULT_UNKNOWN.getCode(),"文档请求结果未确认，请核查原文档或任务状态；不要自动重复创建、追加或删除",failure);
        }
    }
    /** 旧文档入口兼容原地址；新v2目录使用wecom.加平台真实工具名称。 */
    private ImBotDocumentDTO executeWecom(ImBotEntity bot,ImBotDocumentRequestDTO request)throws Exception {
        Map<String,Object> args=request.getArguments()==null?new LinkedHashMap<>():new LinkedHashMap<>(request.getArguments());
        if(JSONUtil.toJsonStr(args).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024*1024)throw BizException.paramError("文档参数不能超过1MB");
        String tool=request.getAction();
        if(tool==null || !tool.startsWith("wecom."))throw BizException.paramError("企微文档动作须使用能力接口返回的wecom.工具名");
        tool=tool.substring("wecom.".length());
        String endpoint=documentEndpoint(bot);
        for(ImBotDocumentAction legacy:values())if(legacy.getCode().equals(request.getAction())) {
            tool=legacy.getPath();
            // 只转换已验证的基础文档语义；复杂表格旧参数不静默转换为新版结构。
            if(HttpUrl.get(endpoint).encodedPath().equals("/mcp/v2/bot/doc")) {
                if(legacy==WECOM_DOC_CREATE) {
                    tool="doc_create";Object type=args.get("doc_type");
                    if(Integer.valueOf(3).equals(type))args.put("doc_type","doc");
                    else if(Integer.valueOf(10).equals(type))args.put("doc_type","smartsheet");
                    args.putIfAbsent("content","");
                } else if(legacy==WECOM_DOC_WRITE) {
                    tool="doc_contents_overwrite";
                    if(Integer.valueOf(1).equals(args.get("content_type")))args.put("content_type","markdown");
                } else throw BizException.paramError("该表格动作的旧参数不适用于v2，请通过tool/page获取当前工具和参数");
            }
            break;
        }
        Map<String,Object> data=wecom.call(endpoint,tool,args);
        ImBotDocumentDTO result=new ImBotDocumentDTO();result.setPlatform(bot.getPlatform());result.setConfigured(true);result.setData(data);
        log.info("机器人文档操作完成 botKey={}, tool={}, userId={}, tenantId={}",bot.getId(),tool,UserInfoUtil.getUserId(),UserInfoUtil.getTenantId());
        return result;
    }
    private String documentEndpoint(ImBotEntity bot) {
        String endpoint=bots.toolMcpUrls(bot).get(ImBotMcpCapability.DOCUMENT.getCode());
        if(StringUtils.isBlank(endpoint))throw new BizException(Error.DOCUMENT_NOT_CONFIGURED.getCode(),"请先配置该机器人的文档授权地址");
        return endpoint;
    }
    /** 白名单参数与资源标识校验先于平台认证和调用。 */
    static void validate(ImBotDocumentAction action,Map<String,Object> arguments) {
        Set<String> allowed=new HashSet<>(action.getPathFields());allowed.addAll(action.getQueryFields());allowed.addAll(action.getBodyFields());
        if(!allowed.containsAll(arguments.keySet()))throw BizException.paramError("此文档操作包含不支持的参数，请查询能力接口");
        if(JSONUtil.toJsonStr(arguments).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024*1024)
            throw BizException.paramError("单次文档参数不能超过1MB，请分段处理");
        for(String key:action.getPathFields()) {
            Object value=arguments.get(key);
            if(!(value instanceof String) || !((String)value).matches("[A-Za-z0-9_-]{1,256}"))throw BizException.paramError("文档资源标识无效："+key);
        }
        if(arguments.containsKey("page_size")) {
            String page=String.valueOf(arguments.get("page_size"));
            if(!page.matches("[0-9]{1,3}") || Integer.parseInt(page)<1 || Integer.parseInt(page)>100)throw BizException.paramError("page_size须为1至100");
        }
    }
    /** 固定上游 URL，路径标识经过严格校验；正文和查询参数只允许动作声明字段。 */
    static String url(ImBotDocumentAction action,Map<String,Object> args) {
        String path=action.getPath();for(String key:action.getPathFields())path=path.replace("{"+key+"}",(String)args.get(key));
        HttpUrl.Builder builder=HttpUrl.get("https://open.feishu.cn/open-apis/"+path).newBuilder();
        for(String key:action.getQueryFields())if(args.get(key)!=null) {
            Object value=args.get(key);
            if(value instanceof Collection) {
                if("ranges".equals(key))builder.addQueryParameter(key,StringUtils.join((Collection<?>)value,","));
                else builder.addQueryParameter(key,JSONUtil.toJsonStr(value));
            } else builder.addQueryParameter(key,String.valueOf(value));
        }
        return builder.build().toString();
    }
    private Map<String,Object> feishu(ImBotEntity bot,ImBotDocumentAction action,Map<String,Object> args,ImBotDocumentRequestDTO request)throws Exception {
        String secret=AesCryptoUtil.decrypt(AesCryptoUtil.resolveKey(environment),bot.getSecretCipher());
        Map<String,String> headers=ImBotFeishuAuth.headers(bot.getExternalAppId(),secret);
        if(action==FEISHU_FILE_UPLOAD || action==FEISHU_MEDIA_UPLOAD)return upload(action,args,request,headers);
        if(action==FEISHU_EXPORT_DOWNLOAD) {
            if(StringUtils.isBlank(request.getSessionId()))throw BizException.paramError("导出下载须指定本人sessionId");
            transaction.execute(status->fileOwnership.requireUpload(UserInfoUtil.getTenantId(),UserInfoUtil.getUserId(),request.getSessionId()));
            if(!(args.get("file_name") instanceof String) || StringUtils.isBlank((String)args.get("file_name")))throw BizException.paramError("导出下载须指定带扩展名的file_name");
            String name=ImBotFile.safeName((String)args.get("file_name"));
            try(ImBotFile file=ImBotFile.download(url(action,args),headers,name,null)) {
                ReqConversationFileUploadDTO upload=new ReqConversationFileUploadDTO();upload.setSessionId(request.getSessionId());upload.setUploadType(UploadTypeEnum.SESSION.getCode());
                ConversationFileUploadDTO saved=conversations.uploadFile(upload,file);
                return JSONUtil.createObj().set("fileId",String.valueOf(saved.getFileId())).set("conversationFileId",String.valueOf(saved.getConversationFileId()))
                        .set("sessionId",request.getSessionId()).set("fileName",saved.getFileName()).set("fileSize",saved.getFileSize());
            }
        }
        JSONObject body=JSONUtil.createObj();for(String key:action.getBodyFields())if(args.containsKey(key))body.set(key,args.get(key));
        OkHttpClientUtil.HttpResult response=OkHttpClientUtil.jsonNoRetry(action.getMethod(),url(action,args),headers,body.toString());
        return requireFeishu(response,bot.getId(),action.getCode());
    }
    private Map<String,Object> upload(ImBotDocumentAction action,Map<String,Object> args,ImBotDocumentRequestDTO request,Map<String,String> headers)throws Exception {
        if(StringUtils.isBlank(request.getSessionId()) || request.getConversationFileId()==null)throw BizException.paramError("上传须指定本人sessionId和conversationFileId");
        FileDownloadDTO source=conversations.getConversationDownloadFile(request.getSessionId(),request.getConversationFileId());
        String parentType=action==FEISHU_FILE_UPLOAD?FileParentType.EXPLORER.getCode():String.valueOf(args.get("parent_type"));
        if(action==FEISHU_MEDIA_UPLOAD && !Arrays.asList(FileParentType.DOCX_IMAGE.getCode(),FileParentType.DOCX_FILE.getCode()).contains(parentType))throw BizException.paramError("文档素材parent_type仅支持docx_image或docx_file");
        long limit=20L*1024*1024;
        if(source.getFileSize()==null || source.getFileSize()>limit)throw new BizException(Error.FILE_TOO_LARGE.getCode(),"飞书单次文件上传不能超过20MB");
        try(InputStream input=storage.getObject(source.getBucketName(),source.getObjectKey());
            ImBotFile local=ImBotFile.copyOf(input,source.getFileName(),"application/octet-stream",limit)) {
            MultipartBody body=new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file_name",local.getOriginalFilename()).addFormDataPart("parent_type",parentType)
                    .addFormDataPart("parent_node",String.valueOf(args.getOrDefault("parent_node","")))
                    .addFormDataPart("size",String.valueOf(local.getSize()))
                    .addFormDataPart("file",local.getOriginalFilename(),RequestBody.create(local.path().toFile(),MediaType.get("application/octet-stream"))).build();
            return requireFeishu(OkHttpClientUtil.postFileNoRetry(url(action,args),headers,body),request.getBotKey(),action.getCode());
        }
    }
    /** 保留平台业务码、日志号和缺失权限，避免将包含正文或凭据的完整失败响应回传。 */
    static Map<String,Object> requireFeishu(OkHttpClientUtil.HttpResult response,Long botKey,String action) {
        JSONObject result=JSONUtil.parseObj(response.getBody());Integer code=result.getInt("code",-1);
        if(!response.isSuccessful() || code!=0) {
            JSONObject error=result.getJSONObject("error");List<String> scopes=new ArrayList<>();
            if(error!=null && error.getJSONArray("permission_violations")!=null) {
                JSONArray violations=error.getJSONArray("permission_violations");
                for(int i=0;i<violations.size();i++) {
                    String scope=violations.getJSONObject(i).getStr("subject");
                    if(scope!=null && scope.matches("[A-Za-z0-9_:.-]{1,128}"))scopes.add(scope);
                }
            }
            String logId=error==null?null:error.getStr("log_id");
            if(logId!=null && !logId.matches("[A-Za-z0-9_-]{1,128}"))logId=null;
            log.warn("飞书文档平台拒绝 botKey={}, action={}, httpStatus={}, providerCode={}, logId={}, requiredScopes={}",botKey,action,response.getStatusCode(),code,logId,scopes);
            throw new BizException(!scopes.isEmpty() || response.getStatusCode()==403?Error.DOCUMENT_PERMISSION_DENIED.getCode():Error.DOCUMENT_FAILED.getCode(),
                    "飞书文档请求失败，平台code="+code+"，logId="+logId+(!scopes.isEmpty()?"，缺少权限："+String.join(",",scopes):"；请检查对应 API 权限和目标文档对机器人应用的授权"));
        }
        JSONObject data=result.getJSONObject("data");return data==null?Collections.emptyMap():data;
    }
}
