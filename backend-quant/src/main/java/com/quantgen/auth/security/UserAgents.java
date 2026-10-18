package com.quantgen.auth.security;

/** Coarse, display-only device and browser names from a User-Agent header. Never used for security decisions. */
final class UserAgents {

    record Parsed(String device, String browser) {
    }

    private UserAgents() {
    }

    static Parsed parse(String ua) {
        if (ua == null || ua.isBlank()) {
            return new Parsed("Unknown device", "Unknown browser");
        }
        String device;
        if (ua.contains("Android")) {
            device = "Android";
        } else if (ua.contains("iPhone") || ua.contains("iPad")) {
            device = "iOS";
        } else if (ua.contains("Windows")) {
            device = "Windows";
        } else if (ua.contains("Mac OS X") || ua.contains("Macintosh")) {
            device = "macOS";
        } else if (ua.contains("Linux")) {
            device = "Linux";
        } else {
            device = "Unknown device";
        }
        // Order matters: Edge and Opera also say "Chrome", Chrome also says "Safari".
        String browser;
        if (ua.contains("Edg/") || ua.contains("Edge/")) {
            browser = "Edge";
        } else if (ua.contains("OPR/") || ua.contains("Opera")) {
            browser = "Opera";
        } else if (ua.contains("Firefox/")) {
            browser = "Firefox";
        } else if (ua.contains("Chrome/") || ua.contains("CriOS/")) {
            browser = "Chrome";
        } else if (ua.contains("Safari/")) {
            browser = "Safari";
        } else {
            browser = "Unknown browser";
        }
        return new Parsed(device, browser);
    }
}
