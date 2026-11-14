/*
 * ABI 标记库（非业务代码）。
 *
 * M1 的 Kotlin/OkHttp 实现没有原生依赖，但 SPEC AC7 要求发布物仅含 arm64-v8a：
 * aapt2 只有在 APK 内存在 lib/arm64-v8a 目录下的 .so 时才会输出 native-code 元数据。
 * 因此附带一个空的 arm64 标记库，保证 APK 被标记为 arm64-only，并避免误装到
 * 32 位/其他 ABI 设备。该库不会被 Java/Kotlin 加载，也不含任何逻辑。
 *
 * 构建：见 scripts/build-apk.sh（NDK clang --target=aarch64-linux-android31）。
 */
int dsh_mobile_abi_marker(void) { return 0; }
