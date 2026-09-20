package com.community.residence.agreement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.agreement.entity.AgreementTemplate;
import org.apache.ibatis.annotations.Mapper;

/* 租赁协议模板：全局模板（community_id 为空）与社区模板共存，可见性在 Service 层约束 */
@Mapper
public interface AgreementTemplateMapper extends BaseMapper<AgreementTemplate> {
}
