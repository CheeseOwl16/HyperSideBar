cmake_minimum_required(VERSION 3.22)
project(hypersidebar_native C)

add_library(hypersidebar_native SHARED hypersidebar_native.c)

# 纯 C、不用 STL；日志走 liblog
target_link_libraries(hypersidebar_native PRIVATE log)

# 导出面只放行 native_init（官方 ABI 要求 + 参考实现 exports-lsposed.map 同款）；
# max-page-size=16384 是硬要求：marble OS3（Android 16）为 16KB 页内核，默认 4096
# 对齐的 .so 在其上 dlopen 必失败——且 LSPosed 对 native entry 失败的处理是整个模块
# 不加载（Java 入口一起死，20260929 实机实证：探针 0x1000 对齐=模块全哑，修对齐前
# 无任何日志）。libdexkit.so 实测即 0x4000。
target_link_options(hypersidebar_native PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports-lsposed.map"
    "-Wl,-z,max-page-size=16384")
target_compile_options(hypersidebar_native PRIVATE
    -fvisibility=hidden -Wl,-z,now)
