// Top-level build file
plugins {
    id("com.android.application") version "8.10.1" apply false
    // 必须与传递依赖拉进来的 kotlin-stdlib 2.2.x 对齐，否则 metadata 版本冲突
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
