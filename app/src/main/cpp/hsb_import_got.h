/*
 * 导入符号 → GOT 槽解析（native 结构校验层「底座」第二层）。
 *
 * 来源（Apache-2.0）：参考工程 MiuiBackGestureHook 的
 *   _repos/MiuiBackGestureHook/miui-home-hyos-native/runtime_profile_resolver.cpp
 *   的 BoundedStringEquals / FindImportGot / ResolveImports 与 RequiredImports 结构。
 * 上游为 Apache License 2.0；本文件是衍生作品，保留来源与许可说明
 * （见 README「第三方组件与许可」）。
 *
 * 层次位置：建在 hsb_elf_view.{h,c} 之上——它负责"这个偏移在不在段内"，
 * 本文件负责"这个符号名对应的 GOT 槽在哪个偏移"；再上层 hsb_a64_decode.{h,c}
 * 用这个 GOT 槽去证明"某个函数体的 bl 确实落到了这个槽的 PLT 桩"。
 *
 * 对 RequiredImports 的裁剪（上游 15 个字段全是 MIUI Home 自己的 Dart/Runtime
 * 业务符号，与本仓库无关）：换成"调用方给的 (名字, GOT) 槽数组"，字段只留实际
 * 用到的两个 —— name / got。语义与上游保持一致的只有一条：**全有或全无**，
 * 任一符号解析不出来（不存在、重名、越界、GOT 槽不可读）就整体失败返回 0，
 * 不做"部分成功"。
 *
 * 为什么这些边界检查一个字都不能省（本文件独有的三条风险）：
 *   1) BoundedStringEquals —— 符号名是"在 dynstr 里按 st_name 偏移找到的、不一定
 *      以 NUL 结尾"的字节串；用 strcmp 会一路读到相邻符号甚至段外。所以按 available
 *      （= strsz - st_name）限长比较，并且要求期望串的结尾处那个字节真的在范围内。
 *   2) FindImportGot —— `.rela.plt` 的 r_info 里取出的符号下标来自库本身，
 *      先用 (SIZE_MAX - symtab)/sizeof(Sym) 证明 `symtab + idx*sizeof(Sym)` 不回绕，
 *      再用 Contains 证明那条符号表项真的可读，才 memcpy；解析出的 got 槽同样要
 *      归一化 + Contains(PF_R) 才认。
 *   3) 唯一性 —— 同一个导入名在 GOT 表里出现两次（如互斥的两个 PLT 桩）说明我们
 *      对这个库的假设不成立，必须拒（match_count != 1 ⇒ false）；否则后续
 *      "调用点落到哪个槽"的判定会把两个不同语义的桩当成同一个。
 * 上游靠这些检查在 Android 17 桌面上 fail-closed。移植时**没有删除、没有放宽**。
 */
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "hsb_elf_view.h"

/* 一个待解析的导入槽：name 由调用方填（ELF dynsym 里的符号名，与上游
 * kMotionActionMasked 之类的常量同源），got 由 hsb_import_resolve 填。
 * got 是**视图内偏移**（不是运行时绝对地址），0 也是合法偏移，所以"未解析"
 * 只能靠函数返回值判断，不能靠 got == 0。 */
typedef struct {
    const char *name;
    uintptr_t got;
} HsbImportSlot;

/* 限长比较：value 在 [0, available) 内必须是 expected 且其后紧跟 NUL。
 * available 通常由调用方算成 (strsz - st_name)。任一指针为 NULL 即 0。 */
int hsb_import_bounded_string_equals(const char *value, size_t available,
                                     const char *expected);

/* 在 .rela.plt 里找唯一一条指向 expected 的符号，输出其 GOT 槽（视图内偏移）。
 * 0 命中或 ≥2 命中、或任一环节越界/不可读 ⇒ 返回 0。 */
int hsb_import_find_got(const HsbElfView *view, const char *expected,
                        uintptr_t *result);

/* 逐个解析 slots[0..count) 的 name。全部成功才返回 1；任一失败立即返回 0。
 * 失败时已填的槽不保证有意义（调用方以返回值为准）。 */
int hsb_import_resolve(const HsbElfView *view, HsbImportSlot *slots,
                       size_t count);
