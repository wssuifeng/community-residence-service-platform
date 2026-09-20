/**
 * R61 租约续约支付（需求规格 v1.4，决策日志 2026-09-20）。
 * 职责：支付渠道凭据门控（支付宝/微信）、续约支付单生命周期
 * （PENDING → SUCCESS / CLOSED）、主动查单落账（延展租期 + 双方通知）。
 */
package com.community.residence.payment;
