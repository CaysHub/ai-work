package cn.com.servyou.work.web.converter;

import cn.com.servyou.work.api.model.imbot.*;
import cn.com.servyou.work.web.request.imbot.*;
import cn.com.servyou.work.web.response.imbot.*;
import cn.com.servyou.work.api.exception.BizException;
import org.springframework.beans.BeanUtils;
import java.time.ZoneId;

public final class ImBotWebConverter {
    private ImBotWebConverter() { }
    /** 接管配置标识统一在Web边界转换。 */
    public static ImBotTakeoverRequestDTO toDTO(ReqImBotTakeoverVO vo){
        if(vo==null)throw BizException.paramError("请求体必填");ImBotTakeoverRequestDTO dto=new ImBotTakeoverRequestDTO();BeanUtils.copyProperties(vo,dto);
        dto.setBotKey(WebIdConverter.toLong(vo.getBotKey(),"botKey"));dto.setExpectedConfigVersion(WebIdConverter.toVersion(vo.getExpectedConfigVersion(),"expectedConfigVersion"));return dto;
    }
    /** OAuth请求只接收code及state，不接受user_access_token。 */
    public static ImBotTakeoverRequestDTO toDTO(ReqImBotTakeoverOAuthVO vo){
        if(vo==null)throw BizException.paramError("请求体必填");ImBotTakeoverRequestDTO dto=new ImBotTakeoverRequestDTO();BeanUtils.copyProperties(vo,dto);
        dto.setBotKey(WebIdConverter.toLong(vo.getBotKey(),"botKey"));dto.setExpectedConfigVersion(WebIdConverter.toVersion(vo.getExpectedConfigVersion(),"expectedConfigVersion"));return dto;
    }
    /** 状态中的主键和版本以字符串返回，敏感配置没有对应VO字段。 */
    public static RespImBotTakeoverVO toVO(ImBotTakeoverDTO dto){
        RespImBotTakeoverVO vo=new RespImBotTakeoverVO();BeanUtils.copyProperties(dto,vo);
        vo.setBotKey(String.valueOf(dto.getBotKey()));vo.setConfigVersion(String.valueOf(dto.getConfigVersion()));return vo;
    }
    /** 工具请求标识在Web边界校验后转换为内部数字。 */
    public static ImBotToolRequestDTO toDTO(ReqImBotToolVO vo) {
        if(vo==null)throw BizException.paramError("请求体必填");
        ImBotToolRequestDTO dto=new ImBotToolRequestDTO();BeanUtils.copyProperties(vo,dto);
        dto.setBotKey(WebIdConverter.toLong(vo.getBotKey(),"botKey"));
        dto.setConversationFileId(WebIdConverter.toNullableLong(vo.getConversationFileId(),"conversationFileId"));return dto;
    }
    /** 工具Schema按平台原类型返回，不包含本地身份和授权地址。 */
    public static RespImBotToolVO toVO(ImBotToolDTO dto) {
        RespImBotToolVO vo=new RespImBotToolVO();BeanUtils.copyProperties(dto,vo);return vo;
    }
    /** 将文档请求中的字符串标识转换为内部数字类型。 */
    public static ImBotDocumentRequestDTO toDTO(ReqImBotDocumentVO vo) {
        if(vo==null) throw BizException.paramError("请求体必填");
        ImBotDocumentRequestDTO dto=new ImBotDocumentRequestDTO();BeanUtils.copyProperties(vo,dto);
        dto.setBotKey(WebIdConverter.toLong(vo.getBotKey(),"botKey"));
        dto.setConversationFileId(WebIdConverter.toNullableLong(vo.getConversationFileId(),"conversationFileId"));
        return dto;
    }
    /** 文档返回只包含平台业务结果，本地文件主键以字符串写入结果。 */
    public static RespImBotDocumentVO toVO(ImBotDocumentDTO dto) {
        RespImBotDocumentVO vo=new RespImBotDocumentVO();BeanUtils.copyProperties(dto,vo);return vo;
    }
    public static ImBotQueryDTO toDTO(ReqImBotQueryVO vo) {
        if(vo==null) vo=new ReqImBotQueryVO();
        ImBotQueryDTO dto=new ImBotQueryDTO(); BeanUtils.copyProperties(vo,dto);
        dto.setStartTime(vo.getStartTime()==null?null:vo.getStartTime().atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
        dto.setEndTime(vo.getEndTime()==null?null:vo.getEndTime().atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
        dto.setBotKey(WebIdConverter.toNullableLong(vo.getBotKey(),"botKey"));
        return dto;
    }
    public static ImBotSaveDTO toDTO(ReqImBotSaveVO vo) {
        if(vo==null) throw BizException.paramError("请求体必填");
        ImBotSaveDTO dto=new ImBotSaveDTO(); BeanUtils.copyProperties(vo,dto);
        dto.setBotKey(WebIdConverter.toNullableLong(vo.getBotKey(),"botKey"));
        dto.setExpectedConfigVersion(vo.getExpectedConfigVersion()==null?null:WebIdConverter.toVersion(vo.getExpectedConfigVersion(),"expectedConfigVersion"));
        return dto;
    }
    public static ImBotOperationDTO toDTO(ReqImBotOperationVO vo) {
        if(vo==null) throw BizException.paramError("请求体必填");
        ImBotOperationDTO dto=new ImBotOperationDTO(); BeanUtils.copyProperties(vo,dto);
        dto.setBotKey(WebIdConverter.toNullableLong(vo.getBotKey(),"botKey"));
        dto.setExpectedConfigVersion(vo.getExpectedConfigVersion()==null?null:WebIdConverter.toVersion(vo.getExpectedConfigVersion(),"expectedConfigVersion"));
        dto.setMessageId(WebIdConverter.toNullableLong(vo.getMessageId(),"messageId"));
        return dto;
    }
    public static ImBotSendDTO toDTO(ReqImBotSendVO vo) {
        if(vo==null) throw BizException.paramError("请求体必填");
        ImBotSendDTO dto=new ImBotSendDTO(); BeanUtils.copyProperties(vo,dto);
        dto.setBotKey(WebIdConverter.toNullableLong(vo.getBotKey(),"botKey"));
        dto.setTargetSessionKey(WebIdConverter.toNullableLong(vo.getTargetSessionKey(),"targetSessionKey"));
        dto.setConversationFileId(WebIdConverter.toNullableLong(vo.getConversationFileId(),"conversationFileId"));
        return dto;
    }
    public static RespImBotVO toVO(ImBotDTO dto) {
        if(dto==null)return null;RespImBotVO vo=new RespImBotVO();BeanUtils.copyProperties(dto,vo);
        vo.setActiveBotKey(dto.getActiveBotKey()==null?null:String.valueOf(dto.getActiveBotKey()));
        vo.setActiveBotConfigVersion(dto.getActiveBotConfigVersion()==null?null:String.valueOf(dto.getActiveBotConfigVersion()));
        vo.setBotKey(dto.getBotKey()==null?null:String.valueOf(dto.getBotKey()));
        vo.setConfigVersion(dto.getConfigVersion()==null?null:String.valueOf(dto.getConfigVersion()));
        vo.setLastHeartbeatAt(dto.getLastHeartbeatAt()==null?null:dto.getLastHeartbeatAt().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime());
        return vo;
    }
    public static RespImBotUserVO toVO(ImBotUserDTO dto) {
        if(dto==null)return null;RespImBotUserVO vo=new RespImBotUserVO();BeanUtils.copyProperties(dto,vo);
        vo.setBotKey(dto.getBotKey()==null?null:String.valueOf(dto.getBotKey()));
        vo.setBoundAt(dto.getBoundAt()==null?null:dto.getBoundAt().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime());
        return vo;
    }
    public static RespImBotSessionVO toVO(ImBotSessionDTO dto) {
        if(dto==null)return null;RespImBotSessionVO vo=new RespImBotSessionVO();BeanUtils.copyProperties(dto,vo);
        vo.setTargetSessionKey(dto.getTargetSessionKey()==null?null:String.valueOf(dto.getTargetSessionKey()));
        vo.setBotKey(dto.getBotKey()==null?null:String.valueOf(dto.getBotKey()));
        vo.setLastInteractionAt(dto.getLastInteractionAt()==null?null:dto.getLastInteractionAt().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime());
        return vo;
    }
    public static RespImBotMessageVO toVO(ImBotMessageDTO dto) {
        if(dto==null)return null;RespImBotMessageVO vo=new RespImBotMessageVO();BeanUtils.copyProperties(dto,vo);
        vo.setMessageId(dto.getMessageId()==null?null:String.valueOf(dto.getMessageId()));
        vo.setBotKey(dto.getBotKey()==null?null:String.valueOf(dto.getBotKey()));
        vo.setTargetSessionKey(dto.getTargetSessionKey()==null?null:String.valueOf(dto.getTargetSessionKey()));
        vo.setDbMessageId(dto.getDbMessageId()==null?null:String.valueOf(dto.getDbMessageId()));
        vo.setFileId(dto.getFileId()==null?null:String.valueOf(dto.getFileId()));
        vo.setCreatedAt(dto.getCreatedAt()==null?null:dto.getCreatedAt().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime());
        vo.setExpiresAt(dto.getExpiresAt()==null?null:dto.getExpiresAt().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime());
        vo.setUpdatedAt(dto.getUpdatedAt()==null?null:dto.getUpdatedAt().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime());
        return vo;
    }
    public static RespImBotBindingCodeVO toVO(ImBotBindingCodeDTO dto) {
        if(dto==null)return null;RespImBotBindingCodeVO vo=new RespImBotBindingCodeVO();BeanUtils.copyProperties(dto,vo);
        vo.setExpiresAt(dto.getExpiresAt()==null?null:dto.getExpiresAt().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime());
        return vo;
    }
}
