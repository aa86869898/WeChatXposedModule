================================================================================
  VerifySettingAdapter 验证插件 — 使用说明
================================================================================

已部署位置: LSPilot_Plugins/VerifySettingAdapter/main.java
导出源码:   /sdcard/VerifySettingAdapter.java

================================================================================
验证目标
================================================================================

  [1] setAdapter 是否触发
  [2] Adapter 类名是否为 f34.g
  [3] 父类是否为 zn3.t0

================================================================================
操作步骤
================================================================================

  1. 打开 LSPilot → 插件管理 → 勾选 VerifySettingAdapter
  2. 打开微信 → 我 → 设置
  3. 查看日志:
     Android/media/com.tencent.mm/LSPilot/Plugin/VerifySettingAdapter/log/

================================================================================
预期输出
================================================================================

========== VerifySettingAdapter 插件已加载 ==========
========== Hook 已就绪，等待触发 ==========

========================================
★ 命中! RecyclerView.setAdapter()
========================================
[验证1] setAdapter 触发: ✅ YES
[验证2] Adapter 类名: f34.g
         → ✅ 确认为 f34.g (匹配!)
[验证3] 父类: zn3.t0
         → ✅ 父类为 zn3.t0 (匹配!)
--- 完整继承链 ---
  ↳ f34.g
  ↳ zn3.t0
  ↳ com.tencent.mm.view.recyclerview.WxRecyclerAdapter
  ↳ po5.n0
  ↳ com.tencent.mm.ui.recyclerview.SynchronizedAdapter
  ↳ androidx.recyclerview.widget.RecyclerView$Adapter
  ↳ java.lang.Object
--- RecyclerView 类型 ---
  RecyclerView: com.tencent.mm.view.recyclerview.WxRecyclerView
  → ✅ 确认为 WxRecyclerView
========================================
★ 验证完成!
========================================

================================================================================
