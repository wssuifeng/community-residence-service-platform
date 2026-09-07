/**
 * 横切模块 - 定时任务。
 * 职责：租期判定扫描（每日 01:00）、到期提醒触发（去重表）、公告自动下线
 * （每小时）、浏览统计回写（每 5 分钟）、任务执行日志（sys_task_log）。
 */
package com.community.residence.schedule;
