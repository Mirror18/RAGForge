package com.ragforge.ingestion.connector;

import java.util.List;
import java.util.regex.Pattern;

final class GlobRules {
    private GlobRules() { }

    static boolean included(String path, DiscoveryRules rules) {
        boolean include = rules.include().isEmpty() || rules.include().stream().anyMatch(pattern -> matches(pattern, path));
        boolean excluded = rules.exclude().stream().anyMatch(pattern -> matches(pattern, path));
        return include && !excluded;
    }

    static boolean supported(String path, DiscoveryRules rules) {
        if (rules.supportedExtensions().isEmpty()) {
            return true;
        }
        int dot = path.lastIndexOf('.');
        return dot >= 0 && rules.supportedExtensions().contains(path.substring(dot).toLowerCase());
    }

    private static boolean matches(String rawPattern, String path) {
        String pattern = CanonicalPath.normalize(rawPattern);
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                boolean globStar = i + 1 < pattern.length() && pattern.charAt(i + 1) == '*';
                if (globStar) {
                    if (i + 2 < pattern.length() && pattern.charAt(i + 2) == '/') {
                        regex.append("(?:.*/)?");
                        i += 2;
                    } else {
                        regex.append(".*");
                        i++;
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.append("$").toString()).matcher(path).matches();
    }
}
