<script setup lang="ts">
import type { CommunityDeletionImpact } from '@/common/community/api/community'
defineProps<{ impacts: CommunityDeletionImpact[] }>()
</script>

<template>
  <section class="deletion-impact" aria-label="小区删除影响">
    <div class="risk-heading"><span class="risk-icon" aria-hidden="true">!</span><div><strong>此操作无法撤回</strong><p>请先核对小区和关联记录，再继续确认名称。</p><p class="retention-brief">账号本身保留 · 不自动退租或退款</p></div></div>
    <article v-for="impact in impacts" :key="impact.communityId" class="community-impact">
      <h3>{{ impact.communityName }}</h3>
      <dl class="impact-grid">
        <div><dt>房屋</dt><dd>{{ impact.houses }}<small>套</small></dd></div>
        <div><dt>居住关系</dt><dd>{{ impact.relations }}<small :class="{ 'active-risk': impact.activeRelations > 0 }">有效 {{ impact.activeRelations }}</small></dd></div>
        <div><dt>租约</dt><dd>{{ impact.leases }}<small :class="{ 'active-risk': impact.activeLeases > 0 }">进行中 {{ impact.activeLeases }}</small></dd></div>
        <div><dt>入住申请</dt><dd>{{ impact.applications }}<small>待审核 {{ impact.pendingApplications }}</small></dd></div>
        <div><dt>支付记录</dt><dd>{{ impact.payments }}<small>条</small></dd></div>
        <div><dt>退款记录</dt><dd>{{ impact.refunds }}<small>条</small></dd></div>
      </dl>
    </article>
    <div class="deletion-consequences"><strong>将永久删除</strong><p>小区下的楼栋、单元、房屋、公共资源、挂牌及关联业务记录，包含有效居住关系、进行中租约和支付退款记录。</p></div>
    <div class="retention-note"><span>账号本身保留</span><p>删除不等于退租或退款，也不会自动退还任何资金。</p></div>
  </section>
</template>

<style scoped>
.deletion-impact { color: #163047; font-size: 14px; line-height: 1.65; min-width: 0; }
.risk-heading { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 20px; }
.risk-icon { flex: 0 0 30px; width: 30px; height: 30px; display: grid; place-items: center; background: #fff1f2; color: #b42332; border: 1px solid #fecdd3; border-radius: 8px; font-weight: 800; font-size: 19px; }
.risk-heading .retention-brief { font-size: 12px; color: #92400e; }
.risk-heading strong { font-size: 15px; color: #b42332; }
p { margin: 4px 0 0; overflow-wrap: anywhere; }
.risk-heading p, dt, .retention-note p { color: #64748b; }
.community-impact + .community-impact { margin-top: 20px; }
h3 { margin: 0 0 12px; font-size: 18px; font-weight: 650; overflow-wrap: anywhere; }
.impact-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); margin: 0 0 18px; border: 1px solid #dbe3ec; border-radius: 10px; overflow: hidden; }
.impact-grid > div { padding: 12px 16px; background: #f8fafc; border-bottom: 1px solid #dbe3ec; }
.impact-grid > div:nth-child(odd) { border-right: 1px solid #dbe3ec; }
.impact-grid > div:nth-last-child(-n+2) { border-bottom: 0; }
dt { font-size: 12px; }
dd { margin: 3px 0 0; display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; font-size: 24px; font-weight: 650; font-variant-numeric: tabular-nums; line-height: 1.35; }
dd small { font-size: 12px; font-weight: 450; color: #64748b; }
dd small.active-risk { color: #b42332; font-weight: 650; }
.deletion-consequences { border-left: 3px solid #b42332; padding: 10px 12px; background: #fff1f2; border-radius: 0 6px 6px 0; }
.deletion-consequences strong { color: #b42332; }
.deletion-consequences p, .retention-note { font-size: 13px; }
.retention-note { margin-top: 12px; }
.retention-note span { font-weight: 600; }
:global(.community-deletion-dialog.el-message-box) { width: min(580px, calc(100vw - 32px)); max-width: calc(100vw - 32px); border-radius: 14px; padding: 0; }
:global(.community-deletion-dialog .el-message-box__header) { padding: 20px 24px 12px; }
:global(.community-deletion-dialog .el-message-box__title) { font-size: 18px; font-weight: 650; color: #163047; }
:global(.community-deletion-dialog .el-message-box__content) { padding: 6px 24px 20px; max-height: calc(100dvh - 164px); overflow-y: auto; }
:global(.community-deletion-dialog .el-message-box__message) { width: 100%; min-width: 0; }
:global(.community-deletion-dialog .el-message-box__btns) { display: flex; gap: 12px; flex-wrap: wrap; padding: 16px 24px; border-top: 1px solid #dbe3ec; background: #f8fafc; }
:global(.community-deletion-dialog .el-message-box__btns .el-button) { margin: 0; min-height: 40px; padding: 10px 18px; border-radius: 8px; font-weight: 550; }
:global(.community-deletion-dialog .community-delete-cancel) { color: #334155; background: white; border-color: #cbd5e1; }
:global(.community-deletion-dialog .community-delete-next) { color: #92400e; background: #fffbeb; border-color: #f0c77a; }
:global(.community-deletion-dialog .community-delete-final) { color: white; background: #b42332; border-color: #b42332; }
:global(.community-deletion-dialog .community-delete-final:hover) { background: #941d29; border-color: #941d29; }
:global(.community-deletion-dialog button:focus-visible) { outline: 3px solid #e8a4ab; outline-offset: 3px; }
:global(.community-deletion-dialog .delete-final-name) { display: block; font-size: 19px; margin: 10px 0; color: #b42332; overflow-wrap: anywhere; }
@media (max-width: 420px) {
  .impact-grid > div { padding: 10px 12px; }
  dd { font-size: 21px; gap: 6px; }
  :global(.community-deletion-dialog .el-message-box__header), :global(.community-deletion-dialog .el-message-box__content) { padding-left: 16px; padding-right: 16px; }
  :global(.community-deletion-dialog .el-message-box__btns) { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 14px 16px; gap: 8px; }
  :global(.community-deletion-dialog .el-message-box__btns .el-button) { width: 100%; padding: 10px 8px; }
}
@media (max-width: 330px) { .impact-grid { grid-template-columns: minmax(0, 1fr); } .impact-grid > div:nth-child(n) { border-right: 0; border-bottom: 1px solid #dbe3ec; } .impact-grid > div:last-child { border-bottom: 0; } }
</style>
