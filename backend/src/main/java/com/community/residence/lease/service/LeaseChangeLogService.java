package com.community.residence.lease.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.lease.entity.LeaseChangeLog;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseChangeLogMapper;
import com.community.residence.lease.vo.LeaseChangeVO;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 租约变更留痕（字段级）：登记、属性编辑、续租、状态流转、协议签约五类动作统一由此写入，
 * 保证任何改动租约关键属性的路径都留下前后值，避免各业务方法各自拼日志造成漏记。
 * 属性类变更按字段逐条 diff，只记录真正发生变化的字段（无变化不写噪声记录）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaseChangeLogService {

    /** 参与属性 diff 的字段：contract_url 仅作附件留痕，不参与 diff 展示 */
    private static final String[] DIFF_FIELD_NAMES = {"startDate", "endDate", "monthlyRent", "deposit", "remark"};
    private static final String[] DIFF_FIELD_LABELS = {"租期开始日期", "租期结束日期", "月租金", "押金", "备注"};

    private final LeaseChangeLogMapper changeLogMapper;
    private final ResidentMapper residentMapper;
    private final SysUserMapper sysUserMapper;

    /* 登记租约：一条 CREATE 记录，摘要首期关键属性便于回溯初始约定 */
    public void logCreate(LeaseRecord lease, String reason) {
        String summary = String.format("租期 %s ~ %s，月租 %s，押金 %s",
                lease.getStartDate(), lease.getEndDate(),
                formatMoney(lease.getMonthlyRent()), formatMoney(lease.getDeposit()));
        record(lease.getId(), "CREATE", null, "登记租约", null, summary, reason);
    }

    /* 属性编辑：逐字段 diff（编辑月租/押金/到期时间等） */
    public void logAttributeChanges(LeaseRecord before, LeaseRecord after, String reason) {
        writeDiff("ATTRIBUTE", before, after, reason);
    }

    /* 续租：同样逐字段 diff，但类型标记为 RENEW 便于按「续租」筛选 */
    public void logRenew(LeaseRecord before, LeaseRecord after, String reason) {
        writeDiff("RENEW", before, after, reason);
    }

    /* 状态流转：一条记录承载 from → to */
    public void logStatus(Long leaseId, String from, String to, String reason) {
        record(leaseId, "STATUS", "status", "租住状态", from, to, reason);
    }

    /* 协议签约动作（发起/确认/撤回）留痕，正文快照存 lease_agreement，此处只记动作 */
    public void logAgreement(Long leaseId, String action, String detail) {
        record(leaseId, "AGREEMENT", "agreementStatus", "协议签约", null, action, detail);
    }

    public List<LeaseChangeVO> list(Long leaseId) {
        return changeLogMapper.selectList(new LambdaQueryWrapper<LeaseChangeLog>()
                        .eq(LeaseChangeLog::getLeaseId, leaseId)
                        .orderByDesc(LeaseChangeLog::getId))
                .stream().map(LeaseChangeVO::from).toList();
    }

    private void writeDiff(String changeType, LeaseRecord before, LeaseRecord after, String reason) {
        if (before == null || after == null) {
            return;
        }
        String[] current = {
                str(before.getStartDate()), str(before.getEndDate()),
                formatMoney(before.getMonthlyRent()), formatMoney(before.getDeposit()),
                before.getRemark()
        };
        String[] target = {
                str(after.getStartDate()), str(after.getEndDate()),
                formatMoney(after.getMonthlyRent()), formatMoney(after.getDeposit()),
                after.getRemark()
        };
        List<LeaseChangeLog> pending = new ArrayList<>();
        for (int i = 0; i < DIFF_FIELD_NAMES.length; i++) {
            if (Objects.equals(current[i], target[i])) {
                continue;
            }
            LeaseChangeLog entry = newEntry(after.getId(), changeType, reason);
            entry.setFieldName(DIFF_FIELD_NAMES[i]);
            entry.setFieldLabel(DIFF_FIELD_LABELS[i]);
            entry.setOldValue(blankToNull(current[i]));
            entry.setNewValue(blankToNull(target[i]));
            pending.add(entry);
        }
        if (pending.isEmpty()) {
            log.debug("租约属性无实际变化，不写变更历史：leaseId={}", after.getId());
            return;
        }
        pending.forEach(changeLogMapper::insert);
    }

    private void record(Long leaseId, String changeType, String fieldName, String fieldLabel,
                        String oldValue, String newValue, String reason) {
        LeaseChangeLog entry = newEntry(leaseId, changeType, reason);
        entry.setFieldName(fieldName);
        entry.setFieldLabel(fieldLabel);
        entry.setOldValue(oldValue);
        entry.setNewValue(newValue);
        changeLogMapper.insert(entry);
    }

    /* 操作人快照：定时任务等无登录上下文时记 SYSTEM，避免历史记录指向空操作人 */
    private LeaseChangeLog newEntry(Long leaseId, String changeType, String reason) {
        LeaseChangeLog entry = new LeaseChangeLog();
        entry.setLeaseId(leaseId);
        entry.setChangeType(changeType);
        entry.setReason(reason);
        UserContext user = SecurityUtils.getUser();
        if (user == null) {
            entry.setOperatorType("SYSTEM");
            entry.setOperatorName("系统");
            return entry;
        }
        entry.setOperatorId(user.getUserId());
        if (RoleConstants.RESIDENT.equals(user.getRole())) {
            entry.setOperatorType("RESIDENT");
            Resident resident = residentMapper.selectById(user.getUserId());
            entry.setOperatorName(resident != null ? resident.getRealName() : user.getUsername());
        } else {
            entry.setOperatorType("ADMIN");
            SysUser sysUser = sysUserMapper.selectById(user.getUserId());
            entry.setOperatorName(sysUser != null && StringUtils.hasText(sysUser.getRealName())
                    ? sysUser.getRealName() : user.getUsername());
        }
        return entry;
    }

    private String formatMoney(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String str(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
