package xyz.daikirai.music;

import java.io.File;
import java.util.Locale;

public final class AudioRuntime {

    private AudioRuntime() {
    }

    private static final boolean ANDROID = detectAndroid();
    private static final boolean NATIVE_JAVA_SOUND = detectNativeJavaSound();

    public static boolean isAndroid() {
        return ANDROID;
    }

    public static boolean hasNativeJavaSound() {
        return NATIVE_JAVA_SOUND;
    }

    public static boolean preferGameAudio() {
        return ANDROID || !NATIVE_JAVA_SOUND;
    }

    private static boolean detectAndroid() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("android")) {
            return true;
        }
        String runtime = (System.getProperty("java.runtime.name", "") + " "
                + System.getProperty("java.vm.name", "")).toLowerCase(Locale.ROOT);
        if (runtime.contains("android") || runtime.contains("dalvik")) {
            return true;
        }
        if (!os.contains("linux")) {
            return false;
        }
        if ("/system".equals(System.getenv("ANDROID_ROOT"))
                && "/data".equals(System.getenv("ANDROID_DATA"))) {
            return true;
        }
        String pojav = System.getenv("POJAV_NATIVEDIR");
        if (pojav != null && !pojav.trim().isEmpty()) {
            return true;
        }
        return new File("/system/build.prop").isFile()
                || new File("/system/bin/app_process").isFile();
    }

    private static boolean detectNativeJavaSound() {
        String home = System.getProperty("java.home");
        if (home == null) {
            return true;
        }
        String[] candidates = {
                "bin/jsound.dll",
                "lib/libjsound.so",
                "lib/libjsound.dylib",
                "lib/amd64/libjsound.so",
                "lib/aarch64/libjsound.so",
        };
        for (String candidate : candidates) {
            if (new File(home, candidate).isFile()) {
                return true;
            }
        }
        return false;
    }
}
