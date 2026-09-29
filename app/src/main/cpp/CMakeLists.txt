cmake_minimum_required(VERSION 3.22)
project(hypersidebar_native C)

add_library(hypersidebar_native SHARED hypersidebar_native.c)

# 纯 C、不用 STL；日志走 liblog
target_link_libraries(hypersidebar_native PRIVATE log)

# 导出面只放行 native_init（官方 ABI 要求 + 参考实现 exports-lsposed.map 同款）
target_link_options(hypersidebar_native PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports-lsposed.map")
target_compile_options(hypersidebar_native PRIVATE
    -fvisibility=hidden -Wl,-z,now)
