package io.arkx.os.fleet.app.boot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * boot 本地配置读取（BOOT-REDESIGN.md §3.3 / §12 阶段 4.1）。
 *
 * <p>
 * 只读两个本地文件（极简解析，零第三方依赖）：
 * </p>
 * <ul>
 * <li>{@code config.yml}：{@code startup.mode/version}、{@code app.maven-repo-path}（{@code {m2.repo}}）、
 * {@code app.arkxos-home}（{@code ${ARKXOS_HOME}}）与 {@code env:} 段（通用
 * {@code ${KEY}} 占位符
 * 补充表）——全部配置的统一入口；</li>
 * <li>{@code registry.json}：RegistryStore 数组格式，按 appId 定位版本（latest →
 * publishedAt 最大）。</li>
 * </ul>
 *
 * <p>
 * 占位符值表（§3.3）：{@code {app.home}}（installed 目录）、{@code {m2.repo}}、
 * {@code ${JAVA_HOME}} / {@code ${ARKXOS_HOME}} / config.yml {@code env:}
 * 段声明的键——替换统一交给
 * command-core 的 {@code CommandCore.resolve}（boot 只负责取值，不自行拼接）。
 * </p>
 */
public final class BootConfig {

    /** {@code ${ARKXOS_HOME}} 占位符键；config.yml {@code app.arkxos-home} 统一提供该值 */
    public static final String ARKXOS_HOME_KEY = "ARKXOS_HOME";

    private static final Pattern ENTRY = Pattern
            .compile(
                    "\\{[^{}]*?\"appId\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\"version\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\"publishedAt\"\\s*:\\s*(\\d+)[^{}]*?\\}");

    private final Path home;
    private final String configYml;
    private final String registryJson;

    private BootConfig(Path home, String configYml, String registryJson) {
        this.home = home;
        this.configYml = configYml;
        this.registryJson = registryJson;
    }

    /** 从 home 根目录读两文件（config.yml / registry.json 缺失视为空）。 */
    public static BootConfig load(Path home) throws IOException {
        String config = readOptional(home.resolve("config.yml"));
        String registry = readOptional(home.resolve("appstore").resolve("registry.json"));
        return new BootConfig(home, config, registry);
    }

    private static String readOptional(Path file) throws IOException {
        return Files.isRegularFile(file)
                ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                : "";
    }

    // ------------------------------------------------------------------
    // 取值（含优先级）
    // ------------------------------------------------------------------

    /**
     * {@code {m2.repo}} 优先级：config.yml {@code app.maven-repo-path} →
     * {@code ~/.m2/settings.xml} 的 {@code <localRepository>}（机器级默认仓库事实来源，
     * dist 重打包清空 config.yml 后仍能正确定位）→ 兜底 {@code ~/.m2/repository}。
     */
    public String mavenRepoPath() {
        String fromConfig = ymlValue(configYml, "app", "maven-repo-path");
        if (fromConfig != null && !fromConfig.isBlank()) {
            return fromConfig;
        }
        String fromSettings = localRepositoryFromSettings(
                Path.of(System.getProperty("user.home"), ".m2", "settings.xml"));
        if (fromSettings != null && !fromSettings.isBlank()) {
            return fromSettings;
        }
        return System.getProperty("user.home") + java.io.File.separator
                + ".m2" + java.io.File.separator + "repository";
    }

    /** 读 settings.xml 的 {@code <localRepository>}；文件缺失/解析失败返回 null。 */
    static String localRepositoryFromSettings(Path settings) {
        if (settings == null || !Files.isRegularFile(settings)) {
            return null;
        }
        try {
            return localRepositoryFromSettingsXml(
                    new String(Files.readAllBytes(settings), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /** 从 settings.xml 文本提取 {@code <localRepository>} 值（简化解析：取首个非空标签）。 */
    static String localRepositoryFromSettingsXml(String xml) {
        if (xml == null) {
            return null;
        }
        Matcher m = Pattern.compile("<localRepository>([^<]+)</localRepository>").matcher(xml);
        if (!m.find()) {
            return null;
        }
        String v = m.group(1).trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * config.yml {@code app.arkxos-home}（{@code ${ARKXOS_HOME}} 占位符值；未配置返回 null）。
     */
    public String arkxosHome() {
        String v = ymlValue(configYml, "app", "arkxos-home");
        return v == null || v.isBlank() ? null : v;
    }

    /** config.yml {@code startup.mode}（默认 server）。 */
    public String startupMode() {
        String v = ymlValue(configYml, "startup", "mode");
        return v == null || v.isBlank() ? "server" : v;
    }

    /** config.yml {@code startup.version}（默认 latest）。 */
    public String startupVersion() {
        String v = ymlValue(configYml, "startup", "version");
        return v == null || v.isBlank() ? "latest" : v;
    }

    /**
     * 占位符值表：config.yml {@code env:} 段键值 + {@code app.arkxos-home}（作为
     * {@code ${ARKXOS_HOME}}，
     * 专用键优先，env: 段同名键被覆盖）。
     * 未显式配置 ARKXOS_HOME（config 与 env: 段均无）时兜底注入部署根（boot 所在目录）：
     * 保证子进程 AppHome 以部署根为基准读取 config.yml（ui=true/port=9091 等），
     * 而非回退到 installed 目录读生成的默认配置导致 UI 永不启动。
     * 不含系统环境变量，系统回退由 command-core 做。
     */
    public Map<String, String> envVars() {
        Map<String, String> merged = ymlSection(configYml, "env");
        String home = arkxosHome();
        if (home == null) {
            home = merged.get(ARKXOS_HOME_KEY);
        }
        if (home == null) {
            home = this.home.toString();
        }
        merged.put(ARKXOS_HOME_KEY, home);
        return merged;
    }

    /** registry.json 原始文本（供单测断言与后续 start/switch 复用）。 */
    String registryRaw() {
        return registryJson;
    }

    // ------------------------------------------------------------------
    // 版本定位（registry）
    // ------------------------------------------------------------------

    /**
     * 按 appId 定位版本：version={@code latest} 时取该 appId 下 publishedAt 最大的条目版本；
     * 否则按传入值返回（不校验存在性，交给调用方）。
     */
    public String resolveVersion(String appId, String version) {
        if (version != null && !"latest".equalsIgnoreCase(version)) {
            return version;
        }
        String best = null;
        long bestAt = Long.MIN_VALUE;
        Matcher m = ENTRY.matcher(registryJson);
        while (m.find()) {
            if (!appId.equals(m.group(1))) {
                continue;
            }
            long at = Long.parseLong(m.group(3));
            if (at > bestAt) {
                bestAt = at;
                best = m.group(2);
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 极简解析（纯字符串/正则，无 YAML/JSON 依赖）
    // ------------------------------------------------------------------

    /**
     * 极简 yml 取值：定位 {@code section:} 段内（至下一个顶格行）的 {@code key:} 行。
     * 段/键均不做复杂结构解析，适配 boot 只关心的扁平键。
     */
    static String ymlValue(String yml, String section, String key) {
        if (yml == null || yml.isBlank()) {
            return null;
        }
        int si = indexOfLine(yml, section + ":");
        if (si < 0) {
            return null;
        }
        int searchStart = yml.indexOf('\n', si);
        if (searchStart < 0) {
            return null;
        }
        searchStart++;
        int searchEnd = nextTopLevelLine(yml, searchStart);
        String sectionBody = searchEnd > searchStart
                ? yml.substring(searchStart, searchEnd)
                : yml.substring(searchStart);
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*:\\s*(.*?)\\s*$").matcher(sectionBody);
        if (!m.find()) {
            return null;
        }
        String v = m.group(1);
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    /**
     * 极简 yml 段解析：返回 {@code section:} 段内（至下一个顶格行）全部 {@code key: value} 键值对。
     * 跳过注释行与空行；值支持单/双引号包裹（Windows 路径用单引号常见）。
     */
    static Map<String, String> ymlSection(String yml, String section) {
        Map<String, String> map = new LinkedHashMap<>();
        if (yml == null || yml.isBlank()) {
            return map;
        }
        int si = indexOfLine(yml, section + ":");
        if (si < 0) {
            return map;
        }
        int searchStart = yml.indexOf('\n', si);
        if (searchStart < 0) {
            return map;
        }
        searchStart++;
        int searchEnd = nextTopLevelLine(yml, searchStart);
        String sectionBody = searchEnd > searchStart
                ? yml.substring(searchStart, searchEnd)
                : yml.substring(searchStart);
        Matcher m = Pattern.compile("(?m)^\\s*([^#:\\s][^:]*?)\\s*:\\s*(.*?)\\s*$").matcher(sectionBody);
        while (m.find()) {
            String v = m.group(2);
            if (v.length() >= 2
                    && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
                v = v.substring(1, v.length() - 1);
            }
            map.put(m.group(1), v);
        }
        return map;
    }

    /** 找 line 文本在 yml 中出现的行起始下标（整行匹配，避免命中子串）。 */
    private static int indexOfLine(String yml, String line) {
        Pattern p = Pattern.compile("(?m)^\\s*" + Pattern.quote(line) + "\\s*$");
        Matcher m = p.matcher(yml);
        return m.find() ? m.start() : -1;
    }

    /** 从 from 起找下一个顶格非空行（即下一个段），返回其行首下标；没有则返回 -1。 */
    private static int nextTopLevelLine(String yml, int from) {
        int pos = from;
        while (pos < yml.length()) {
            int eol = yml.indexOf('\n', pos);
            String line = eol >= 0 ? yml.substring(pos, eol) : yml.substring(pos);
            if (!line.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                return pos;
            }
            if (eol < 0) {
                return -1;
            }
            pos = eol + 1;
        }
        return -1;
    }
}
