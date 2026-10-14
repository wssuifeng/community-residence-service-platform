-- 协议消息由服务端生成；旧普通消息仍为TEXT，不从内容猜测业务链接。
ALTER TABLE conversation_message
    ADD COLUMN message_type VARCHAR(30) NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/LEASE_AGREEMENT，服务端业务类型',
    ADD COLUMN agreement_id BIGINT UNSIGNED NULL COMMENT '协议快照弱引用；文本客户端不可指定';
