package android.os;

/**
 * 隐藏 API 桩：只提供编译期签名，运行时使用平台的 android.os.SystemProperties。
 * 与同目录下 ServiceManager / ActivityThread 等桩同类，模块以 compileOnly 方式引入。
 */
public class SystemProperties {
    public static String get(String key) {
        throw new RuntimeException("Stub!");
    }

    public static String get(String key, String def) {
        throw new RuntimeException("Stub!");
    }
}
