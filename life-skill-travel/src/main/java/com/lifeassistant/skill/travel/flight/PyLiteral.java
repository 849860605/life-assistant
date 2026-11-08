package com.lifeassistant.skill.travel.flight;

/**
 * 把 Python 字面量（飞常准返回的是 Python 的 dict 打印：单引号字符串、None、True、False）转成 JSON 文本。
 * 只处理字符串、关键字和标点，数字和结构原样保留。
 */
final class PyLiteral {

    private PyLiteral() {
    }

    static String toJson(String py) {
        StringBuilder out = new StringBuilder(py.length() + 16);
        int i = 0;
        while (i < py.length()) {
            char c = py.charAt(i);
            if (c == '\'' || c == '"') {
                i = string(py, i, c, out);
            } else if (py.startsWith("None", i) && boundary(py, i, 4)) {
                out.append("null");
                i += 4;
            } else if (py.startsWith("True", i) && boundary(py, i, 4)) {
                out.append("true");
                i += 4;
            } else if (py.startsWith("False", i) && boundary(py, i, 5)) {
                out.append("false");
                i += 5;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 读一个用 quote 引起来的字符串（支持反斜杠转义），按 JSON 写出，返回字符串之后的位置。 */
    private static int string(String py, int start, char quote, StringBuilder out) {
        out.append('"');
        int i = start + 1;
        while (i < py.length()) {
            char c = py.charAt(i);
            if (c == '\\' && i + 1 < py.length()) {
                char n = py.charAt(i + 1);
                switch (n) {
                    case '\'' -> out.append('\'');
                    case '"' -> out.append("\\\"");
                    case 'n' -> out.append("\\n");
                    case 't' -> out.append("\\t");
                    case '\\' -> out.append("\\\\");
                    default -> out.append('\\').append(n);
                }
                i += 2;
                continue;
            }
            if (c == quote) {
                out.append('"');
                return i + 1;
            }
            if (c == '"') {
                out.append("\\\"");
            } else if (c == '\n') {
                out.append("\\n");
            } else {
                out.append(c);
            }
            i++;
        }
        out.append('"');
        return i;
    }

    /** 关键字前后不是字母数字下划线（避免把 "Nonexistent" 之类拆开）。 */
    private static boolean boundary(String s, int at, int len) {
        boolean before = at == 0 || !Character.isLetterOrDigit(s.charAt(at - 1)) && s.charAt(at - 1) != '_';
        int end = at + len;
        boolean after = end >= s.length() || !Character.isLetterOrDigit(s.charAt(end)) && s.charAt(end) != '_';
        return before && after;
    }
}
