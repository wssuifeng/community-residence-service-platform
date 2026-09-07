/**
 * C12 房源展示与看房预约。
 * 职责：房源管理（仅限在管社区房源）、浏览统计（Redis 实时计数定时回写）、
 * 看房预约（复用 reservation 冲突校验）、违约处置。
 */
package com.community.residence.housing;
