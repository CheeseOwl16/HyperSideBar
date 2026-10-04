plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lsp.hypersidebar.hiddenapi"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// 纯编译期桩模块（@hide 框架类）：真实实现由设备 boot classloader 提供。
// :app 以 compileOnly(project(":hidden-api")) 引用 ⇒ 桩不进 app dex。
// 禁止给桩类写任何逻辑；禁止加 proguard keep（keep 会把桩保进 dex，重新制造遮蔽）。
