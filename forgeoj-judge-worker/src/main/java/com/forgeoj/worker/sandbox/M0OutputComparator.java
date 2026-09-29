package com.forgeoj.worker.sandbox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class M0OutputComparator {

    boolean matches(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    private String normalize(String value) {
        String portable = value.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(Arrays.asList(portable.split("\n", -1)));
        for (int index = 0; index < lines.size(); index++) {
            lines.set(index, stripTrailingHorizontalWhitespace(lines.get(index)));
        }
        while (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return String.join("\n", lines);
    }

    private String stripTrailingHorizontalWhitespace(String line) {
        int end = line.length();
        while (end > 0) {
            char candidate = line.charAt(end - 1);
            if (candidate != ' ' && candidate != '\t') {
                break;
            }
            end--;
        }
        return line.substring(0, end);
    }
}
