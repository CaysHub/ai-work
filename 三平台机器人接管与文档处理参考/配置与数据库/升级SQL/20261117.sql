-- 20261117 机器人模块相关表

ALTER TABLE sw_im_bot_message
    ADD COLUMN file_id BIGINT DEFAULT NULL COMMENT '已保存的输入文档或待发送成果文件主键，关联sw_file.id' AFTER content_type,
    MODIFY COLUMN content_type VARCHAR(16) NOT NULL COMMENT '内容类型：TEXT文本、MARKDOWN正文、FILE文档';

ALTER TABLE sw_im_bot
    ADD COLUMN document_config_cipher TEXT DEFAULT NULL COMMENT '统一主密钥加密的企微七类MCP授权配置，兼容旧文档地址，仅后端读取' AFTER secret_cipher;
