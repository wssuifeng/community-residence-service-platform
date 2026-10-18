package com.community.residence.common.agreement.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.agreement.entity.AgreementTemplate;
import com.community.residence.common.agreement.entity.LeaseAgreement;
import com.community.residence.common.agreement.mapper.AgreementTemplateMapper;
import com.community.residence.common.agreement.mapper.LeaseAgreementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 社区删除时的agreement域清理；参加管理端协调事务，不能独立提交。 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
public class CommunityAgreementCleanupService {
    private final AgreementTemplateMapper agreementTemplateMapper;
    private final LeaseAgreementMapper leaseAgreementMapper;
    /* 按原级联顺序清理本域，所有条件来自已确认社区或其受限关联ID。 */
    public void removeForLeases(List<Long> leaseIds) {
            leaseAgreementMapper.delete(new LambdaQueryWrapper<LeaseAgreement>()
                    .in(LeaseAgreement::getLeaseId, leaseIds));
    }
    /* 按原级联顺序清理本域，所有条件来自已确认社区或其受限关联ID。 */
    public void removeTemplates(Long id) {
        agreementTemplateMapper.delete(new LambdaQueryWrapper<AgreementTemplate>()
                .eq(AgreementTemplate::getCommunityId, id));

    }
}
