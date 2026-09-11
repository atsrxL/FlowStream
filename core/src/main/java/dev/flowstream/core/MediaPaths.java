package dev.flowstream.core;

import java.util.Locale;

public final class MediaPaths {
    private MediaPaths() { }
    /** Logical relative paths are decoded exactly once, never platform absolute paths. */
    public static String normalize(String value) {
        if (value == null || value.isEmpty() || value.equals("/")) return "";
        String s = value.replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        for (String part : s.split("/", -1)) {
            if (part.equals("..") || part.equals(".") || part.isEmpty())
                throw new IllegalArgumentException("路径不能含有 .、.. 或空层级");
            for (int i = 0; i < part.length(); i++) {
                if (Character.isISOControl(part.charAt(i))) throw new IllegalArgumentException("路径含控制字符");
            }
        }
        return s;
    }
    public static String join(String parent, String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\"))
            throw new IllegalArgumentException("非法文件名");
        return normalize(normalize(parent).isEmpty() ? name : normalize(parent) + "/" + name);
    }
    public static String parent(String path) {
        String n = normalize(path); int at = n.lastIndexOf('/');
        return at < 0 ? "" : n.substring(0, at);
    }
    public static String name(String path) {
        String n = normalize(path); return n.substring(n.lastIndexOf('/') + 1);
    }
    public static boolean video(String path) {
        String n = path.toLowerCase(Locale.ROOT);
        return n.matches(".*\\.(mp4|m4v|mkv|webm|mov|ts|m2ts|avi|mpg|mpeg|3gp|m3u8|mpd)$");
    }
    public static long resumePosition(long position, long duration) {
        return duration > 0 && position >= duration - 1500 ? 0 : Math.max(0, position);
    }
    public static int adjacent(int index, int delta, int size) {
        if ((delta != -1 && delta != 1) || size < 1 || index < 0 || index >= size) return -1;
        int next = index + delta; return next >= 0 && next < size ? next : -1;
    }
}
