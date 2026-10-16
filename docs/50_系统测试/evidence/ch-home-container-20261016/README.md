# CF-02：首页保留原容器的修正

2026-10-16用户反馈上一轮容器改动影响缩窄窗口的观感，要求图片自适应而不是修改容器。

## 实际对照与最小修正

- 对照47da6af：原guest-main为max-width:1200px、顶部24px/左右16px，hero原高度clamp(440px,58vh,620px)。上一批首页特例改成max-width:none/顶部0、下级内容又另设1200px容器。这部分调整现撤回。
- 图片本来使用cover等比覆盖，不是把图片横向拉伸；本次保留该缩放/取景方式，不改原高度，不为完整塞入图片而改变容器。
- 原通栏50vw负边距会把经典纵向滚动条纳入宽度。现在布局只声明inline-size查询容器，hero用50cqw计算实际可用通栏；不改变容器既有宽高/间距、不全站overflow-x:hidden。
- 保留上一批minmax(0,…)和min-width:0等长内容防撑破保护。

CSS单位依据：[W3C CSS Containment Level 3，Container Query Length Units](https://www.w3.org/TR/css-contain-3/#container-lengths)。cqw基于查询容器宽度，不用JS测宽或重建业务组件。

## 验证范围

只做首页相关检查，没有运行后端或前端全量回归，没有读写正常业务或操作交易。

- 失败先行：原容器max-width断言期望1200px，当前先读到none；改后通过。
- `home-layout-regression.py`：5种窗口宽度×正常/稳定滚动条两种场景，共101项几何/样式断言通过；包含原容器宽度和间距、原hero高度、等比cover、真实长标题、无整页横溢、导航固定/回顶部透明。仅合成API响应，浏览器独立headless，不接管用户窗口。
- `npm run build`（含vue-tsc）通过，只是类型/打包检查，不扩展人工回归范围。
- 当前前端5176由Vite加载两处样式修正，后端8084不重启；原数据与渠道不变。人工只需看CF-02构图是否符合预期，不要求重复其余三项或其他业务链。

证据：`home-layout-summary.json`、`home-1366.png`、`home-768.png`。该自动结果仅说明尺寸契约与不溢出，不能替用户判定视觉偏好已满足。
