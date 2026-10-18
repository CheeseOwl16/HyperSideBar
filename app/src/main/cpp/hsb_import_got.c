/*
 * hsb_import_got.c —— 导入符号 → GOT 槽解析（「底座」第二层）。
 *
 * 来源（Apache-2.0）：参考工程 MiuiBackGestureHook
 *   _repos/MiuiBackGestureHook/miui-home-hyos-native/runtime_profile_resolver.cpp
 *   的 BoundedStringEquals / FindImportGot / ResolveImports（RequiredImports 按需裁剪，
 *   见 .h 顶部）。
 *
 * 不 malloc、无全局状态：所有输出都写调用方传入的结构。
 */
#include "hsb_import_got.h"

#include <string.h>

int hsb_import_bounded_string_equals(const char *value, size_t available,
                                     const char *expected) {
    size_t index = 0u;
    if (value == NULL || expected == NULL) return 0;
    while (expected[index] != '\0') {
        if (index >= available || value[index] != expected[index]) {
            return 0;
        }
        ++index;
    }
    return index < available && value[index] == '\0';
}

int hsb_import_find_got(const HsbElfView *view, const char *expected,
                        uintptr_t *result) {
    uintptr_t matched = 0u;
    uint32_t match_count = 0u;
    size_t count;
    size_t index;
    if (view == NULL || expected == NULL || result == NULL) return 0;
    count = view->jump_relocations_size / sizeof(Elf64_Rela);
    for (index = 0u; index < count; ++index) {
        Elf64_Rela relocation = {0};
        size_t symbol_index;
        uintptr_t symbol_offset;
        Elf64_Sym symbol = {0};
        const char *name;
        uintptr_t got_offset = 0u;
        memcpy(&relocation,
               view->base + view->jump_relocations + index * sizeof(Elf64_Rela),
               sizeof(relocation));
        symbol_index = (size_t) ELF64_R_SYM(relocation.r_info);
        /* 下标来自库自己：先证明 symtab + idx*sizeof(Sym) 不回绕，再证明那条
         * 表项可读，才允许 memcpy。任一处不成立就是"库形态不符合预期"⇒整体拒。 */
        if (symbol_index > (SIZE_MAX - view->symbol_table) /
                                   sizeof(Elf64_Sym)) {
            return 0;
        }
        symbol_offset = view->symbol_table + symbol_index * sizeof(Elf64_Sym);
        if (!hsb_elf_contains(view, symbol_offset, sizeof(Elf64_Sym),
                              PF_R, 0u)) {
            return 0;
        }
        memcpy(&symbol, view->base + symbol_offset, sizeof(symbol));
        /* st_name 必须真的落在 dynstr 内，否则名字指针会指到段外。 */
        if (symbol.st_name >= view->string_table_size) return 0;
        name = (const char *) (const void *)
                (view->base + view->string_table + symbol.st_name);
        if (!hsb_import_bounded_string_equals(
                    name, view->string_table_size - symbol.st_name, expected)) {
            continue;
        }
        if (!hsb_elf_normalize_dynamic_pointer(view, relocation.r_offset,
                                               &got_offset) ||
                !hsb_elf_contains(view, got_offset, sizeof(uintptr_t),
                                  PF_R, 0u)) {
            return 0;
        }
        matched = got_offset;
        ++match_count;
    }
    if (match_count != 1u) return 0;
    *result = matched;
    return 1;
}

int hsb_import_resolve(const HsbElfView *view, HsbImportSlot *slots,
                       size_t count) {
    size_t index;
    if (view == NULL || slots == NULL || count == 0u) return 0;
    for (index = 0u; index < count; ++index) {
        slots[index].got = 0u;
        if (slots[index].name == NULL) return 0;
        if (!hsb_import_find_got(view, slots[index].name, &slots[index].got)) {
            return 0;
        }
    }
    return 1;
}
