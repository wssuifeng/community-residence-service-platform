package com.community.residence.agreement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.agreement.entity.LeaseAgreement;
import org.apache.ibatis.annotations.Mapper;

/* 租约协议：按租约查协议流水（含已撤回历史），正文快照入库不再回查模板 */
@Mapper
public interface LeaseAgreementMapper extends BaseMapper<LeaseAgreement> {
}
