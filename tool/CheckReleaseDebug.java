import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CheckReleaseDebug {
    private static final Pattern DEBUG_LITERAL_DECLARATION = Pattern.compile(
        "(?m)^[ \\t]*private[ \\t]+const[ \\t]+val[ \\t]+_debug"
            + "[ \\t]*=[ \\t]*(true|false)[ \\t]*;?[ \\t]*$"
    );
    private static final Pattern DEBUG_DECLARATION_CANDIDATE = Pattern.compile(
        "\\b(?:(?:const\\s+)?(?:val|var)\\s+_debug)\\b"
    );

    private CheckReleaseDebug() {}

    public static void main(String[] arguments) {
        if (arguments.length != 4
            || !"--source".equals(arguments[0])
            || !"--display".equals(arguments[2])) {
            fail("用法：java tool/CheckReleaseDebug.java --source <Kotlin 文件路径> --display <显示路径>", 64);
        }

        Path sourcePath = Path.of(arguments[1]);
        String displayPath = arguments[3];
        if (!Files.isRegularFile(sourcePath)) {
            fail("Release 构建检查失败：未找到 " + displayPath + "。", 1);
        }

        String source;
        try {
            source = Files.readString(sourcePath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            fail("Release 构建检查失败：无法读取 " + displayPath + "。", 1);
            return;
        }

        String maskedSource = maskCommentsAndStrings(source);
        Matcher declarations = DEBUG_LITERAL_DECLARATION.matcher(maskedSource);
        int declarationCount = 0;
        String value = null;
        while (declarations.find()) {
            if (braceDepthAt(maskedSource, declarations.start()) == 0) {
                declarationCount++;
                value = declarations.group(1);
            }
        }

        Matcher declarationCandidates = DEBUG_DECLARATION_CANDIDATE.matcher(maskedSource);
        int declarationCandidateCount = 0;
        while (declarationCandidates.find()) {
            declarationCandidateCount++;
        }

        if (!hasBalancedBraces(maskedSource)
            || declarationCount != 1
            || declarationCandidateCount != 1) {
            fail(
                "Release 构建检查失败：" + displayPath
                    + " 中的 _debug 声明无效；请保留唯一顶层字面量并改为"
                    + " _debug = false 后重试。",
                1
            );
        }
        if ("true".equals(value)) {
            fail(
                "Release 构建已终止：检测到 " + displayPath
                    + " 中 _debug = true，请改为 _debug = false 后重新构建。",
                1
            );
        }

        System.out.println(
            "[OK] Release 构建检查通过：" + displayPath + " 中 _debug = false。"
        );
    }

    private static void fail(String message, int status) {
        System.err.println("[ERROR] " + message);
        System.exit(status);
    }

    private static boolean hasBalancedBraces(String source) {
        return braceDepthAt(source, source.length()) == 0;
    }

    private static int braceDepthAt(String source, int end) {
        int depth = 0;
        for (int index = 0; index < end; index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth < 0) {
                    return -1;
                }
            }
        }
        return depth;
    }

    private static String maskCommentsAndStrings(String source) {
        StringBuilder output = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            if (source.startsWith("//", index)) {
                index = maskUntilLineEnd(source, index, output);
                continue;
            }
            if (source.startsWith("/*", index)) {
                index = maskBlockComment(source, index, output);
                continue;
            }

            char character = source.charAt(index);
            if (character == '"' || character == '\'') {
                index = maskString(source, index, output);
                continue;
            }
            output.append(character);
            index++;
        }
        return output.toString();
    }

    private static int maskUntilLineEnd(
        String source,
        int index,
        StringBuilder output
    ) {
        while (index < source.length()) {
            char character = source.charAt(index++);
            appendMasked(output, character);
            if (character == '\n' || character == '\r') {
                break;
            }
        }
        return index;
    }

    private static int maskBlockComment(
        String source,
        int index,
        StringBuilder output
    ) {
        int depth = 0;
        while (index < source.length()) {
            if (source.startsWith("/*", index)) {
                depth++;
                appendMasked(output, source.charAt(index));
                appendMasked(output, source.charAt(index + 1));
                index += 2;
                continue;
            }
            if (source.startsWith("*/", index)) {
                depth--;
                appendMasked(output, source.charAt(index));
                appendMasked(output, source.charAt(index + 1));
                index += 2;
                if (depth == 0) {
                    break;
                }
                continue;
            }
            appendMasked(output, source.charAt(index++));
        }
        return index;
    }

    private static int maskString(String source, int index, StringBuilder output) {
        char quote = source.charAt(index);
        boolean isTriple = quote == '"' && source.startsWith("\"\"\"", index);
        String delimiter = isTriple ? "\"\"\"" : String.valueOf(quote);
        for (int offset = 0; offset < delimiter.length(); offset++) {
            appendMasked(output, source.charAt(index + offset));
        }
        index += delimiter.length();

        while (index < source.length()) {
            if (source.startsWith(delimiter, index)) {
                for (int offset = 0; offset < delimiter.length(); offset++) {
                    appendMasked(output, source.charAt(index + offset));
                }
                return index + delimiter.length();
            }
            if (!isTriple && source.charAt(index) == '\\') {
                appendMasked(output, source.charAt(index++));
                if (index < source.length()) {
                    appendMasked(output, source.charAt(index++));
                }
                continue;
            }
            appendMasked(output, source.charAt(index++));
        }
        return index;
    }

    private static void appendMasked(StringBuilder output, char character) {
        output.append(character == '\n' || character == '\r' ? character : ' ');
    }
}
