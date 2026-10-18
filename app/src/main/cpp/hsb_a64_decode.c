/*
 * hsb_a64_decode.c —— AArch64 指令解码（「底座」第三层）。
 *
 * 来源（Apache-2.0）：参考工程 MiuiBackGestureHook
 *   _repos/MiuiBackGestureHook/miui-home-hyos-native/runtime_profile_resolver.cpp
 *   的 DecodeAdrp / DecodeAddImmediate / DecodeLdr64Immediate / DecodeAddressPair /
 *   DecodeBlTarget / DecodePltGot / CallTargetsImport / MatchesWords /
 *   InstructionEquals —— 1:1 移植为 C。
 *
 * 只依赖 hsb_elf_view（无 malloc、无全局状态、无日志）。所有读取都经
 * hsb_elf_read_instruction / hsb_elf_contains 证明范围与权限。
 */
#include "hsb_a64_decode.h"

#include <string.h>

int hsb_a64_decode_adrp(uint32_t instruction, uintptr_t pc, uint32_t reg,
                        uintptr_t *target_page) {
    int64_t immediate;
    int64_t page;
    int64_t target;
    if (target_page == NULL || reg > 31u ||
            (instruction & 0x9f00001fu) != (0x90000000u | reg)) {
        return 0;
    }
    /* immhi:immlo（21 位，immlo 在高位）→ 页数，按补码手工符号扩展。 */
    immediate = (int64_t) (((instruction >> 29u) & 0x3u) |
                           (((instruction >> 5u) & 0x7ffffu) << 2u));
    if ((immediate & ((int64_t) 1 << 20u)) != 0) {
        immediate -= (int64_t) 1 << 21u;
    }
    page = (int64_t) (pc & ~(uintptr_t) 0xfffu);
    target = page + immediate * (int64_t) 4096;
    if (target < 0 || (uint64_t) target > (uint64_t) UINTPTR_MAX) {
        return 0;
    }
    *target_page = (uintptr_t) target;
    return 1;
}

int hsb_a64_decode_add_immediate(uint32_t instruction, uint32_t destination,
                                 uint32_t source, uintptr_t *immediate) {
    if (immediate == NULL || destination > 31u || source > 31u ||
            (instruction & 0xffc003ffu) !=
                    (0x91000000u | (source << 5u) | destination)) {
        return 0;
    }
    *immediate = (instruction >> 10u) & 0xfffu;
    return 1;
}

int hsb_a64_decode_ldr64_immediate(uint32_t instruction, uint32_t destination,
                                   uint32_t source, uintptr_t *immediate) {
    if (immediate == NULL || destination > 31u || source > 31u ||
            (instruction & 0xffc003ffu) !=
                    (0xf9400000u | (source << 5u) | destination)) {
        return 0;
    }
    *immediate = ((instruction >> 10u) & 0xfffu) * sizeof(uintptr_t);
    return 1;
}

int hsb_a64_decode_address_pair(const HsbElfView *view,
                                uintptr_t instruction_offset, uint32_t reg,
                                uintptr_t *target) {
    uint32_t adrp = 0u;
    uint32_t add = 0u;
    uintptr_t page = 0u;
    uintptr_t immediate = 0u;
    if (view == NULL || target == NULL) return 0;
    if (!hsb_elf_read_instruction(view, instruction_offset, &adrp)) return 0;
    if (!hsb_elf_read_instruction(view, instruction_offset + 4u, &add)) {
        return 0;
    }
    if (!hsb_a64_decode_adrp(adrp, instruction_offset, reg, &page)) return 0;
    if (!hsb_a64_decode_add_immediate(add, reg, reg, &immediate)) return 0;
    if (hsb_elf_add_overflows(page, immediate)) return 0;
    *target = page + immediate;
    return 1;
}

int hsb_a64_decode_bl_target(const HsbElfView *view,
                             uintptr_t instruction_offset, uintptr_t *target) {
    uint32_t instruction = 0u;
    int64_t immediate;
    int64_t destination;
    if (view == NULL || target == NULL ||
            !hsb_elf_read_instruction(view, instruction_offset, &instruction) ||
            (instruction & 0xfc000000u) != 0x94000000u) {
        return 0;
    }
    immediate = (int64_t) (instruction & 0x03ffffffu);
    if ((immediate & ((int64_t) 1 << 25u)) != 0) {
        immediate -= (int64_t) 1 << 26u;
    }
    destination = (int64_t) instruction_offset + immediate * (int64_t) 4;
    /* 目标必须回得来（非负、可表示）**且**16 字节可读可执行：否则这次 bl
     * 会把后续读取带到未知地址。 */
    if (destination < 0 ||
            (uint64_t) destination > (uint64_t) UINTPTR_MAX ||
            !hsb_elf_contains(view, (uintptr_t) destination, 16u,
                              PF_R | PF_X, 0u)) {
        return 0;
    }
    *target = (uintptr_t) destination;
    return 1;
}

int hsb_a64_decode_plt_got(const HsbElfView *view, uintptr_t plt_offset,
                           uintptr_t *got_offset) {
    uint32_t adrp = 0u;
    uint32_t ldr = 0u;
    uint32_t add = 0u;
    uint32_t branch = 0u;
    uintptr_t page = 0u;
    uintptr_t load_immediate = 0u;
    uintptr_t add_immediate = 0u;
    if (view == NULL || got_offset == NULL) return 0;
    if (!hsb_elf_read_instruction(view, plt_offset, &adrp)) return 0;
    if (!hsb_elf_read_instruction(view, plt_offset + 4u, &ldr)) return 0;
    if (!hsb_elf_read_instruction(view, plt_offset + 8u, &add)) return 0;
    if (!hsb_elf_read_instruction(view, plt_offset + 12u, &branch)) return 0;
    if (!hsb_a64_decode_adrp(adrp, plt_offset, 16u, &page)) return 0;
    if (!hsb_a64_decode_ldr64_immediate(ldr, 17u, 16u, &load_immediate)) {
        return 0;
    }
    if (!hsb_a64_decode_add_immediate(add, 16u, 16u, &add_immediate)) return 0;
    if (load_immediate != add_immediate) return 0;
    if (branch != 0xd61f0220u) return 0;   /* br x17 */
    if (hsb_elf_add_overflows(page, load_immediate)) return 0;
    *got_offset = page + load_immediate;
    return 1;
}

int hsb_a64_call_targets_import(const HsbElfView *view, uintptr_t call_offset,
                                uintptr_t expected_got) {
    uintptr_t plt = 0u;
    uintptr_t got = 0u;
    return hsb_a64_decode_bl_target(view, call_offset, &plt) &&
            hsb_a64_decode_plt_got(view, plt, &got) && got == expected_got;
}

int hsb_a64_matches_words(const HsbElfView *view, uintptr_t offset,
                          const uint32_t *words, size_t count) {
    if (view == NULL || words == NULL || count == 0u ||
            count > SIZE_MAX / sizeof(uint32_t) ||
            !hsb_elf_contains(view, offset, count * sizeof(uint32_t),
                              PF_R | PF_X, 0u)) {
        return 0;
    }
    return memcmp(view->base + offset, words, count * sizeof(uint32_t)) == 0;
}

int hsb_a64_instruction_equals(const HsbElfView *view, uintptr_t offset,
                               uint32_t expected) {
    uint32_t instruction = 0u;
    return hsb_elf_read_instruction(view, offset, &instruction) &&
            instruction == expected;
}
