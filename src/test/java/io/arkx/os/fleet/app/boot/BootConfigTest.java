package io.arkx.os.fleet.app.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * BootConfig 单测（BOOT-REDESIGN.md §12 阶段 4.1）：
 * <ul>
 * <li>两文件读入：config.yml（含 {@code env:} 段）/ registry.json；</li>
 * <li>占位符值表：{@code env:} 段键值 +
 * {@code app.arkxos-home}（{@code ${ARKXOS_HOME}}）；</li>
 * <li>maven-repo-path 读取与兜底 {@code ~/.m2/repository}；</li>
 * <li>registry latest 定位。</li>
 * </ul>
 */
public class BootConfigTest {

    private Path writeHome(Path home, String configYml, String registryJson) throws Exception {
        Files.createDirectories(home.resolve("appstore"));
        if (configYml != null) {
            Files.write(home.resolve("config.yml"), configYml.getBytes(StandardCharsets.UTF_8));
        }
        if (registryJson != null) {
            Files
                    .write(home.resolve("appstore").resolve("registry.json"),
                            registryJson.getBytes(StandardCharsets.UTF_8));
        }
        return home;
    }

    @Test
    public void testLoadConfigAndRegistry() throws Exception {
        Path home = writeHome(Files.createTempDirectory("bootcfg"),
                """
                        startup:
                          mode: server
                          version: latest
                        app:
                          maven-repo-path: "D:/m2repo"
                        env:
                          CUSTOM_KEY: "custom-value"
                        """,
                "[{\"entryId\":\"dist-server\",\"appId\":\"arkxos-fleet-server\",\"version\":\"1.2.0\","
                        + "\"published\":true,\"status\":\"active\",\"publishedAt\":3}]");

        BootConfig cfg = BootConfig.load(home);
        assertEquals("server", cfg.startupMode(), "startup.mode 读取");
        assertEquals("latest", cfg.startupVersion(), "startup.version 读取");
        assertEquals("D:/m2repo", cfg.mavenRepoPath(), "app.maven-repo-path 读取");
        assertEquals("custom-value", cfg.envVars().get("CUSTOM_KEY"), "env: 段键值读入");
        assertEquals("1.2.0", cfg.resolveVersion("arkxos-fleet-server", "latest"),
                "registry latest 定位");
    }

    @Test
    public void testMavenRepoPathFromConfigYml() throws Exception {
        Path home = writeHome(Files.createTempDirectory("bootcfg-prio"),
                "app:\n  maven-repo-path: \"D:/config-repo\"\n", null);

        BootConfig cfg = BootConfig.load(home);
        assertEquals("D:/config-repo", cfg.mavenRepoPath(), "maven-repo-path 统一以 config.yml 为准");
    }

    @Test
    public void testArkxosHomeFromConfigYml() throws Exception {
        Path home = writeHome(Files.createTempDirectory("bootcfg-arkxos"),
                """
                        app:
                          arkxos-home: "D:/config-arkxos"
                        env:
                          ARKXOS_HOME: "D:/env-arkxos"
                        """, null);

        BootConfig cfg = BootConfig.load(home);
        assertEquals("D:/config-arkxos",
                cfg.envVars().get("ARKXOS_HOME"), "${ARKXOS_HOME} 以 config.yml app.arkxos-home 为准（覆盖 env: 段同名键）");
        assertEquals("D:/config-arkxos", cfg.arkxosHome(), "arkxosHome() 读取 config.yml");
    }

    @Test
    public void testArkxosHomeFallsBackToEnvSection() throws Exception {
        Path home = writeHome(Files.createTempDirectory("bootcfg-arkxos-env"),
                """
                        app:
                          maven-repo-path: "D:/m2"
                        env:
                          ARKXOS_HOME: "D:/env-arkxos"
                        """, null);

        BootConfig cfg = BootConfig.load(home);
        assertEquals("D:/env-arkxos",
                cfg.envVars().get("ARKXOS_HOME"), "config.yml 未配 arkxos-home 时沿用 env: 段原值");
        assertNull(cfg.arkxosHome(), "config.yml 未配 arkxos-home 时 arkxosHome() 为 null");
    }

    @Test
    public void testDefaultsWhenFilesMissing() throws Exception {
        Path home = Files.createTempDirectory("bootcfg-default");
        BootConfig cfg = BootConfig.load(home);
        assertEquals("server", cfg.startupMode(), "startup.mode 默认 server");
        assertEquals("latest", cfg.startupVersion(), "startup.version 默认 latest");
        // m2 兜底链：settings.xml localRepository（机器级）→ ~/.m2/repository
        Path realSettings = Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
        String fromSettings = BootConfig.localRepositoryFromSettings(realSettings);
        String expected = fromSettings != null
                ? fromSettings
                : System.getProperty("user.home") + java.io.File.separator
                        + ".m2" + java.io.File.separator + "repository";
        assertEquals(expected, cfg.mavenRepoPath(), "m2 兜底：settings.xml localRepository > ~/.m2/repository");
        assertTrue(cfg.resolveVersion("arkxos-fleet-server", "latest") == null, "无 registry 时 latest 返回 null");
    }

    @Test
    public void testLocalRepositoryFromSettingsXml() {
        assertEquals("D:/m2repo", BootConfig
                .localRepositoryFromSettingsXml(
                        "<settings><localRepository>D:/m2repo</localRepository></settings>"));
        assertNull(BootConfig.localRepositoryFromSettingsXml("<settings/>"), "无 localRepository 返回 null");
        assertNull(BootConfig
                .localRepositoryFromSettingsXml(
                        "<settings><localRepository>   </localRepository></settings>"),
                "空白值返回 null");
        assertNull(BootConfig.localRepositoryFromSettingsXml(null), "null 输入返回 null");
    }

    @Test
    public void testArkxosHomeFallsBackToDeploymentRoot() throws Exception {
        Path home = writeHome(Files.createTempDirectory("bootcfg-arkxos-home"),
                "app:\n  maven-repo-path: \"D:/m2\"\n", null);
        BootConfig cfg = BootConfig.load(home);
        assertEquals(home.toAbsolutePath().toString(),
                cfg.envVars().get("ARKXOS_HOME"), "未配置 ARKXOS_HOME 时注入部署根（boot 所在目录）");
        assertNull(cfg.arkxosHome(), "arkxosHome() 仍只读 config.yml");
    }

    @Test
    public void testResolveVersionExplicit() throws Exception {
        Path home = writeHome(Files.createTempDirectory("bootcfg-explicit"), null,
                "[{\"appId\":\"arkxos-fleet-server\",\"version\":\"2.0.0\",\"publishedAt\":1}]");
        BootConfig cfg = BootConfig.load(home);
        assertEquals("1.0.0", cfg.resolveVersion("arkxos-fleet-server", "1.0.0"),
                "显式版本不做 registry 校验");
    }

    @Test
    public void testYmlSection() {
        Map<String, String> map = BootConfig
                .ymlSection(
                        """
                                app:
                                  maven-repo-path: "D:/m2"
                                env:
                                  # 注释行
                                  A: "1"
                                  B: 'x y'
                                  C: plain
                                """, "env");
        assertEquals(3, map.size(), "3 个键（跳过注释行）");
        assertEquals("1", map.get("A"));
        assertEquals("x y", map.get("B"));
        assertEquals("plain", map.get("C"));
        assertTrue(BootConfig.ymlSection("", "env").isEmpty(), "空输入返回空表");
        assertTrue(BootConfig.ymlSection("app:\n  a: 1\n", "env").isEmpty(), "不存在的段返回空表");
    }

    @Test
    public void testYmlValue() {
        String yml = "startup:\n  mode: server\n  version: latest\napp:\n  maven-repo-path: \"D:/m2\"\n";
        assertEquals("server", BootConfig.ymlValue(yml, "startup", "mode"));
        assertEquals("D:/m2", BootConfig.ymlValue(yml, "app", "maven-repo-path"));
        assertNull(BootConfig.ymlValue(yml, "startup", "port"), "不存在的键返回 null");
        assertNull(BootConfig.ymlValue(yml, "bogus", "mode"), "不存在的段返回 null");
    }

    @Test
    public void testYmlValueSingleQuoted() {
        // Windows 反斜杠路径常用单引号包裹，须剥离引号（引号残留会导致 Path.of 抛 Illegal char）
        String yml = "app:\n  maven-repo-path: 'D:\\.m2\\repository'\n";
        assertEquals("D:\\.m2\\repository", BootConfig.ymlValue(yml, "app", "maven-repo-path"));
    }
}
