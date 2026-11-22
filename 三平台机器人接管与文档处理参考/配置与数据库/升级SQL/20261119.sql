-- 20261119 机器人模块相关表

ALTER TABLE sw_im_bot ADD COLUMN parent_bot_id BIGINT DEFAULT NULL COMMENT '人员接管配置所属的原机器人主键', ADD INDEX idx_im_bot_parent (parent_bot_id);
