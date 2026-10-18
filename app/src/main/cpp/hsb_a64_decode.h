/*
 * AArch64 指令解码（native 结构校验层「底座」第三层）。
 *
 * 来源（Apache-2.0）：参考工程 MiuiBackGestureHook 的
 *   _repos/MiuiBackGestureHook/miui-home-hyos-native/runtime_profile_resolver.cpp
 *   的 DecodeAdrp / DecodeAddImmediate / DecodeLdr64Immediate / DecodeAddressPair /
 *   DecodeBlTarget / DecodePltGot / CallTargetsImport / MatchesWords /
 *   InstructionEquals。
 * 上游为 Apache License 2.0；本文件是衍生作品，保留来源与许可说明
 * （见 README「第三方组件与许可」）。
 *
 * 层次位置：建在 hsb_elf_view.{h,c}（段/权限/范围证明）与 hsb_import_got.{h,c}
 * （符号名 → GOT 槽）之上。它回答的是"这个函数体的这段字节，是不是 `bl` 到了那个
 * 导入的 PLT 桩"——也就是把 anchor 表里"这个符号应该调用 X"的断言，落成一条可
 * 证明的结构事实。所有输入都先经 ReadInstruction/Contains 证明在可执行段内。
 *
 * 为什么这些边界检查一个字都不能省（本文件独有的风险）：
 *   1) 每个解码入口都带着"目标寄存器号/条件码必须与期望完全相等"的掩码比较
 *      （如 adrp 的 `(insn & 0x9f00001f) == (0x90000000 | reg)`）。放宽成"只认
 *      操作码"会把 x0 的 adrp 与 x16 的 adrp 一视同仁，于是所有相对寻址都"命中"。
 *   2) 立即数一律按补码手工符号扩展（adrp 21 位、bl 26 位）——用 uint 直接加会得到
 *      一个巨大的正数，于是"目标地址在段内"这条检查要么恒真（形同没有）要么恒假。
 *   3) 算出的目标地址必须**再被 Contains 证明在可执行段内**（DecodeBlTarget 要看到
 *      16 字节、PLT 桩读 16 字节、MatchesWords 读整段）；否则一次 `bl` 就能把后续
 *      读取带到任意地址——包括未映射页。
 *   4) DecodePltGot 同时校验 PLT 桩的四条指令形状（adrp x16 / ldr x17,[x16,#imm] /
 *      add x16,x16,#imm / br x17）与"两次立即数相等"，再要求 got 落在只读段内。
 *      这是"这个 GOT 槽确实是全局偏移表项"而非任意可读地址的唯一证明。
 * 上游靠这些检查在 Android 17 桌面上 fail-closed。移植时**没有删除、没有放宽**。
 */
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "hsb_elf_view.h"

/* ADRP：相对当前页的 ±4GB 页寻址。pc 是**指令自身**的运行时地址
 * （视图坐标系），reg 是目标寄存器号（0..31，必须与指令编码一致才认）。
 * 输出目标页地址（低 12 位为 0）。 */
int hsb_a64_decode_adrp(uint32_t instruction, uintptr_t pc, uint32_t reg,
                        uintptr_t *target_page);

/* ADD (immediate, 64-bit) 的 12 位无符号立即数，要求 destination/source
 * 与编码一致。输出 *immediate ∈ [0, 0xfff]。 */
int hsb_a64_decode_add_immediate(uint32_t instruction, uint32_t destination,
                                 uint32_t source, uintptr_t *immediate);

/* LDR (immediate, 64-bit) 的缩放后偏移（imm12 * 8），要求寄存器与编码一致。 */
int hsb_a64_decode_ldr64_immediate(uint32_t instruction, uint32_t destination,
                                   uint32_t source, uintptr_t *immediate);

/* 解码 "adrp reg, page; add reg, reg, #imm" 这一对（高地址常量构造），
 * 输出合成后的视图内地址。两条指令都必须按序命中同一寄存器。 */
int hsb_a64_decode_address_pair(const HsbElfView *view,
                                uintptr_t instruction_offset, uint32_t reg,
                                uintptr_t *target);

/* 解码 BL（相对 ±128MB），并证明目标处 16 字节可读可执行。 */
int hsb_a64_decode_bl_target(const HsbElfView *view,
                             uintptr_t instruction_offset, uintptr_t *target);

/* 解析 PLT 桩（adrp x16; ldr x17,[x16,#imm]; add x16,x16,#imm; br x17）
 * 指向的 GOT 槽偏移。四条指令任一形状不符 / 立即数不等 / 槽不可读即失败。 */
int hsb_a64_decode_plt_got(const HsbElfView *view, uintptr_t plt_offset,
                           uintptr_t *got_offset);

/* call_offset 处的 bl 是否落到 expected_got（由 hsb_import_find_got 解出）。
 * 这就是"函数体确实调用了这个导入"的结构证明。 */
int hsb_a64_call_targets_import(const HsbElfView *view, uintptr_t call_offset,
                                uintptr_t expected_got);

/* 逐字节比较 count 条 32 位字（用于函数前言/anchor 特征匹配），
 * 要求整段可读可执行。count==0 一律拒。 */
int hsb_a64_matches_words(const HsbElfView *view, uintptr_t offset,
                          const uint32_t *words, size_t count);

/* 单条指令相等（等价于 ReadInstruction + ==，保留独立入口以便读起来是"断言"）。 */
int hsb_a64_instruction_equals(const HsbElfView *view, uintptr_t offset,
                               uint32_t expected);
