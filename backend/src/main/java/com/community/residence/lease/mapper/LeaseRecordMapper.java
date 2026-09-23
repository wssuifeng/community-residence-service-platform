package com.community.residence.lease.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.lease.entity.LeaseRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租住记录数据访问。
 * 注：本批曾在此加入承约前置（看房已完成 / 租约群聊已建立）两条自持 SQL，
 * 用户 2026-09-22 裁决「看房与群聊是沟通手段、不是承约门槛」后已移除，
 * 承约前置回到「本人 + 待签约」。
 */
@Mapper
public interface LeaseRecordMapper extends BaseMapper<LeaseRecord> {
}
