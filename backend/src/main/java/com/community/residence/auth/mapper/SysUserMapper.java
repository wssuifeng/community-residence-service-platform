package com.community.residence.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.auth.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 系统用户表访问 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /* 按用户名查询（登录用，唯一键 uk_username 保证至多一条） */
    @Select("SELECT id, username, password_hash, real_name, phone, email, avatar_url, role, status, "
            + "created_at, updated_at FROM sys_user WHERE username = #{username}")
    SysUser selectByUsername(@Param("username") String username);
    /* 仅ADMIN候选，目标社区已绑定者优先；范围在服务层校验，不开放系统用户管理。 */
    @Select("<script>SELECT su.id, su.real_name, su.phone, EXISTS(SELECT 1 FROM sys_admin_community sac "
            + "WHERE sac.admin_id=su.id AND sac.community_id=#{communityId}) AS bound_to_community "
            + "FROM sys_user su WHERE su.role='ADMIN' "
            + "<if test='keyword != null and keyword != &quot;&quot;'>AND (su.real_name LIKE CONCAT('%',#{keyword},'%') OR su.phone LIKE CONCAT('%',#{keyword},'%'))</if> "
            + "ORDER BY bound_to_community DESC, su.real_name, su.id</script>")
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.community.residence.community.vo.ResponsibleAdminVO> selectResponsibleCandidates(
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.community.residence.community.vo.ResponsibleAdminVO> page,
            @Param("communityId") Long communityId, @Param("keyword") String keyword);
    /* 与责任人绑定串行，避免绑定和改角色同时通过检查。 */
    @Select("SELECT id, username, password_hash, real_name, phone, email, avatar_url, role, status, created_at, updated_at FROM sys_user WHERE id=#{id} FOR UPDATE")
    SysUser selectByIdForUpdate(@Param("id") Long id);
    /* 同身份空间用户名/手机号均可登录；最多读两条，歧义由认证层拒绝，不猜优先级。 */
    @Select("SELECT id, username, password_hash, real_name, phone, email, avatar_url, role, status, created_at, updated_at FROM sys_user WHERE username = #{identifier} OR phone = #{identifier} LIMIT 2")
    java.util.List<SysUser> selectLoginCandidates(@Param("identifier") String identifier);
}
