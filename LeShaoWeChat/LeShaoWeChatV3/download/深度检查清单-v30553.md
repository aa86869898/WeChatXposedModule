# 深度检查清单 v3.0.553

## 防撤回
- [x] 定位 8.0.79 setter 改名根因（b1/k1/e1/t1/r1/r3 NoSuchMethodError）
- [x] 改用 field_* 字段直接设置（field_content 关键）
- [x] read-back 四重验证（type/talker/isSend/content）
- [x] 失败放弃入库，不产生脏数据

## 头像
- [x] 模块磁盘缓存目录（cacheDir/avatar_cache/<md5>.png）
- [x] 所有成功路径写入磁盘缓存
- [x] 二次打开优先读模块缓存
- [x] modelavatar_d1 改 byName（消除 DexKit MISS 刷屏）

## 红包播报
- [x] 移除 sHandled 依赖（手动抢也播报）
- [x] sSpoken 去重防重复播报
- [x] 语音播报默认开启

## 日志
- [x] MAX_SIZE 512KB → 1MB

## 日志问题
- [x] modelavatar_d1 刷屏已修复
- [ ] MessageMenuHook o0 需反编译 8.0.79 更新类名
- [ ] AdBlocker 锚点需反编译更新
- [ ] activateAll 1 FAIL 需加诊断日志

## 编译
- [x] assembleRelease 通过
- [x] 版本号五处同步 v3.0.553 / 30553
