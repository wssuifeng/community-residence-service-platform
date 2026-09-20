/**
 * 多方会话（R63）：会话/参与者/消息三级模型。
 * 职责：看房预约群聊（预约居民+社区管理员+带看人）、居民-社区管理员直通会话，
 * WS 实时推送（/topic/conversation/{id}，订阅鉴权）+ HTTP 轮询兜底。
 */
package com.community.residence.conversation;
