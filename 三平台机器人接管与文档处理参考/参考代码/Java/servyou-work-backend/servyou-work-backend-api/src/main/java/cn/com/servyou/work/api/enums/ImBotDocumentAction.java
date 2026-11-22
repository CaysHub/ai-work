package cn.com.servyou.work.api.enums;

import cn.com.servyou.work.api.exception.BizException;
import lombok.Getter;
import java.util.*;

/** 文档固定动作与上游接口白名单，调用方不能指定 HTTP 方法、地址或身份。 */
@Getter
public enum ImBotDocumentAction {
    FEISHU_DOC_CREATE("feishu.doc.create", ImBotEnums.Platform.FEISHU, "POST", "docx/v1/documents", "", "folder_token,title"),
    FEISHU_DOC_INFO("feishu.doc.info", ImBotEnums.Platform.FEISHU, "GET", "docx/v1/documents/{document_id}", "", ""),
    FEISHU_DOC_READ("feishu.doc.read", ImBotEnums.Platform.FEISHU, "GET", "docx/v1/documents/{document_id}/raw_content", "lang", ""),
    FEISHU_DOC_BLOCKS("feishu.doc.blocks", ImBotEnums.Platform.FEISHU, "GET", "docx/v1/documents/{document_id}/blocks", "page_size,page_token,document_revision_id", ""),
    FEISHU_DOC_CONVERT("feishu.doc.convert", ImBotEnums.Platform.FEISHU, "POST", "docx/v1/documents/blocks/convert", "", "content_type,content"),
    FEISHU_DOC_APPEND("feishu.doc.append", ImBotEnums.Platform.FEISHU, "POST", "docx/v1/documents/{document_id}/blocks/{block_id}/children", "document_revision_id", "children,index"),
    FEISHU_DOC_INSERT("feishu.doc.insert", ImBotEnums.Platform.FEISHU, "POST", "docx/v1/documents/{document_id}/blocks/{block_id}/descendant", "document_revision_id", "children_id,descendants,index"),
    FEISHU_DOC_UPDATE("feishu.doc.update", ImBotEnums.Platform.FEISHU, "PATCH", "docx/v1/documents/{document_id}/blocks/batch_update", "document_revision_id", "requests"),
    FEISHU_DOC_DELETE_BLOCKS("feishu.doc.delete_blocks", ImBotEnums.Platform.FEISHU, "DELETE", "docx/v1/documents/{document_id}/blocks/{block_id}/children/batch_delete", "document_revision_id", "start_index,end_index"),
    FEISHU_SHEET_CREATE("feishu.sheet.create", ImBotEnums.Platform.FEISHU, "POST", "sheets/v3/spreadsheets", "", "title,folder_token"),
    FEISHU_SHEET_LIST("feishu.sheet.sheets", ImBotEnums.Platform.FEISHU, "GET", "sheets/v3/spreadsheets/{spreadsheet_token}/sheets/query", "", ""),
    FEISHU_SHEET_READ("feishu.sheet.read", ImBotEnums.Platform.FEISHU, "GET", "sheets/v2/spreadsheets/{spreadsheet_token}/values_batch_get", "ranges,valueRenderOption,dateTimeRenderOption", ""),
    FEISHU_SHEET_WRITE("feishu.sheet.write", ImBotEnums.Platform.FEISHU, "PUT", "sheets/v2/spreadsheets/{spreadsheet_token}/values", "", "valueRange"),
    FEISHU_BITABLE_CREATE("feishu.bitable.create", ImBotEnums.Platform.FEISHU, "POST", "bitable/v1/apps", "", "name,folder_token,time_zone"),
    FEISHU_TABLE_LIST("feishu.bitable.tables", ImBotEnums.Platform.FEISHU, "GET", "bitable/v1/apps/{app_token}/tables", "page_size,page_token", ""),
    FEISHU_TABLE_CREATE("feishu.bitable.table_create", ImBotEnums.Platform.FEISHU, "POST", "bitable/v1/apps/{app_token}/tables", "", "table"),
    FEISHU_FIELD_LIST("feishu.bitable.fields", ImBotEnums.Platform.FEISHU, "GET", "bitable/v1/apps/{app_token}/tables/{table_id}/fields", "page_size,page_token,view_id,text_field_as_array", ""),
    FEISHU_FIELD_CREATE("feishu.bitable.field_create", ImBotEnums.Platform.FEISHU, "POST", "bitable/v1/apps/{app_token}/tables/{table_id}/fields", "", "field_name,type,property,ui_type,description"),
    FEISHU_FIELD_UPDATE("feishu.bitable.field_update", ImBotEnums.Platform.FEISHU, "PUT", "bitable/v1/apps/{app_token}/tables/{table_id}/fields/{field_id}", "", "field_name,type,property,ui_type,description"),
    FEISHU_RECORD_LIST("feishu.bitable.records", ImBotEnums.Platform.FEISHU, "GET", "bitable/v1/apps/{app_token}/tables/{table_id}/records", "page_size,page_token,view_id,filter,sort,field_names,text_field_as_array", ""),
    FEISHU_RECORD_SEARCH("feishu.bitable.search_records", ImBotEnums.Platform.FEISHU, "POST", "bitable/v1/apps/{app_token}/tables/{table_id}/records/search", "page_size,page_token", "view_id,field_names,sort,filter,automatic_fields"),
    FEISHU_RECORD_CREATE("feishu.bitable.add_records", ImBotEnums.Platform.FEISHU, "POST", "bitable/v1/apps/{app_token}/tables/{table_id}/records/batch_create", "", "records"),
    FEISHU_RECORD_UPDATE("feishu.bitable.update_records", ImBotEnums.Platform.FEISHU, "POST", "bitable/v1/apps/{app_token}/tables/{table_id}/records/batch_update", "", "records"),
    FEISHU_RECORD_DELETE("feishu.bitable.delete_records", ImBotEnums.Platform.FEISHU, "POST", "bitable/v1/apps/{app_token}/tables/{table_id}/records/batch_delete", "", "records"),
    FEISHU_WIKI_RESOLVE("feishu.wiki.resolve", ImBotEnums.Platform.FEISHU, "GET", "wiki/v2/spaces/get_node", "token,obj_type", ""),
    FEISHU_WIKI_LIST("feishu.wiki.nodes", ImBotEnums.Platform.FEISHU, "GET", "wiki/v2/spaces/{space_id}/nodes", "page_size,page_token,parent_node_token", ""),
    FEISHU_FILE_LIST("feishu.drive.files", ImBotEnums.Platform.FEISHU, "GET", "drive/v1/files", "page_size,page_token,folder_token,order_by,direction", ""),
    FEISHU_FILE_COPY("feishu.drive.copy", ImBotEnums.Platform.FEISHU, "POST", "drive/v1/files/{file_token}/copy", "", "name,type,folder_token"),
    FEISHU_FILE_UPLOAD("feishu.drive.upload", ImBotEnums.Platform.FEISHU, "UPLOAD", "drive/v1/files/upload_all", "", "parent_node"),
    FEISHU_MEDIA_UPLOAD("feishu.media.upload", ImBotEnums.Platform.FEISHU, "UPLOAD", "drive/v1/medias/upload_all", "", "parent_type,parent_node"),
    FEISHU_IMPORT_CREATE("feishu.import.create", ImBotEnums.Platform.FEISHU, "POST", "drive/v1/import_tasks", "", "file_extension,file_token,type,file_name,point"),
    FEISHU_IMPORT_GET("feishu.import.get", ImBotEnums.Platform.FEISHU, "GET", "drive/v1/import_tasks/{ticket}", "", ""),
    FEISHU_EXPORT_CREATE("feishu.export.create", ImBotEnums.Platform.FEISHU, "POST", "drive/v1/export_tasks", "", "file_extension,token,type,sub_id"),
    FEISHU_EXPORT_GET("feishu.export.get", ImBotEnums.Platform.FEISHU, "GET", "drive/v1/export_tasks/{ticket}", "token", ""),
    FEISHU_EXPORT_DOWNLOAD("feishu.export.download", ImBotEnums.Platform.FEISHU, "DOWNLOAD", "drive/v1/export_tasks/file/{file_token}/download", "", "file_name"),
    FEISHU_PERMISSION_CHECK("feishu.permission.check", ImBotEnums.Platform.FEISHU, "GET", "drive/v1/permissions/{token}/members/auth", "type,action", ""),
    WECOM_DOC_CREATE("wecom.doc.create", ImBotEnums.Platform.WECOM, "MCP", "create_doc", "", "doc_type,doc_name"),
    WECOM_DOC_WRITE("wecom.doc.write", ImBotEnums.Platform.WECOM, "MCP", "edit_doc_content", "", "docid,content,content_type"),
    WECOM_SHEET_ADD("wecom.smartsheet.add_sheet", ImBotEnums.Platform.WECOM, "MCP", "smartsheet_add_sheet", "", "docid,properties"),
    WECOM_SHEET_GET("wecom.smartsheet.sheets", ImBotEnums.Platform.WECOM, "MCP", "smartsheet_get_sheet", "", "docid"),
    WECOM_FIELD_ADD("wecom.smartsheet.add_fields", ImBotEnums.Platform.WECOM, "MCP", "smartsheet_add_fields", "", "docid,sheet_id,fields"),
    WECOM_FIELD_UPDATE("wecom.smartsheet.update_fields", ImBotEnums.Platform.WECOM, "MCP", "smartsheet_update_fields", "", "docid,sheet_id,fields"),
    WECOM_FIELD_GET("wecom.smartsheet.fields", ImBotEnums.Platform.WECOM, "MCP", "smartsheet_get_fields", "", "docid,sheet_id"),
    WECOM_RECORD_ADD("wecom.smartsheet.add_records", ImBotEnums.Platform.WECOM, "MCP", "smartsheet_add_records", "", "docid,sheet_id,records");

    /** 技能和接口共用的操作代码。 */
    private final String code;
    /** 适用的平台。 */
    private final ImBotEnums.Platform platform;
    /** 后端固定的上游请求方式或传输类型。 */
    private final String method;
    /** 固定上游路径模板或官方 MCP 工具名称。 */
    private final String path;
    /** 允许的查询参数名。 */
    private final List<String> queryFields;
    /** 允许的请求正文参数名。 */
    private final List<String> bodyFields;
    /** 路径中必须提供的资源标识。 */
    private final List<String> pathFields;

    ImBotDocumentAction(String code, ImBotEnums.Platform platform, String method, String path, String query, String body) {
        this.code=code;this.platform=platform;this.method=method;this.path=path;
        this.queryFields=fields(query);this.bodyFields=fields(body);
        List<String> names=new ArrayList<>();
        for(String segment:path.split("/"))if(segment.startsWith("{") && segment.endsWith("}"))names.add(segment.substring(1,segment.length()-1));
        this.pathFields=Collections.unmodifiableList(names);
    }
    private static List<String> fields(String value) {
        return value.isEmpty()?Collections.emptyList():Collections.unmodifiableList(Arrays.asList(value.split(",")));
    }
    /** 解析固定动作；未知动作不转发到平台。 */
    public static ImBotDocumentAction fromCode(String code) {
        for(ImBotDocumentAction action:values())if(action.code.equals(code))return action;
        throw BizException.paramError("不支持的文档操作");
    }
    /** 平台文件挂载类型，文档接口只开放已实现的三种。 */
    @Getter public enum FileParentType {
        EXPLORER("explorer"), DOCX_IMAGE("docx_image"), DOCX_FILE("docx_file");
        /** 飞书官方挂载类型代码。 */
        private final String code;
        FileParentType(String code){this.code=code;}
    }

}
