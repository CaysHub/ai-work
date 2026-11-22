-- 20261118 机器人模块相关表

ALTER TABLE sw_im_bot ADD COLUMN identity_mode VARCHAR(16) NOT NULL DEFAULT 'BOT' COMMENT '收发身份：BOT机器人，USER授权真人';

ALTER TABLE sw_im_bot ADD COLUMN takeover_config_cipher MEDIUMTEXT DEFAULT NULL COMMENT '统一主密钥加密的真人接管配置、用户授权令牌及会话发现进度，仅后端读取';

ALTER TABLE sw_im_bot_session ADD COLUMN takeover_state MEDIUMTEXT DEFAULT NULL COMMENT '真人会话读取水位、固定分页窗口及待合并消息，仅后端读取';
