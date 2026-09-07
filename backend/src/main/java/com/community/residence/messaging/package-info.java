/**
 * C11 消息与通知中心。
 * 职责：通知生成（Spring 事件驱动，事务内生成）、长连接推送（STOMP，
 * AFTER_COMMIT 异步）、上线补拉（按 seq 全局递增序号）、渠道分级
 * （站内必达/短信/微信模拟通道）。
 */
package com.community.residence.messaging;
