#pragma once
/*
 * 配置读取（native 侧）：**文件优先 → 系统属性 → 编译期缺省**。
 *
 * 为什么要有文件这一路（2026-10-17）：属性通道的两个硬伤——
 *   ① 写属性必须 root（模块 App 目前靠 `su setprop`）⇒ 权限链长、失败静默；
 *   ② 属性值长度受限、且 `debug.*` 重启即清。
 * `/data/local/tmp/hsb.conf` 是**全域可读写**的普通文件：模块 App 不需要 root 就能原子替换
 * （写 .tmp → rename），key/value 行式、可注释、想放多少放多少。
 *
 * 键名约定：文件里用**短名**（去掉 `persist.hsb.` 前缀），例如 `corner.hot_pct=8`；
 * 调用方按完整属性名传参（`persist.hsb.corner.hot_pct`），内部自动剥前缀查找。
 *
 * 读取节流：1 秒粒度 + `stat` 的 mtime/size 判变更 ⇒ 热路径（每个输入事件）成本可忽略。
 * 值非法/越界/NaN/文件缺失 ⇒ 当"缺失"处理（继续往下一来源找，最后用 dflt）——**绝不猜**。
 */
#ifdef __cplusplus
extern "C" {
#endif

/** 读浮点：`[lo, hi]` 之外 / 解析失败 / 缺失 ⇒ dflt。 */
float hsb_cfg_float(const char *key, float lo, float hi, float dflt);

/** 读整数：`[lo, hi]` 之外 / 解析失败 / 缺失 ⇒ dflt。 */
int hsb_cfg_int(const char *key, int lo, int hi, int dflt);

#ifdef __cplusplus
}
#endif
