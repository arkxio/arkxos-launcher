package io.arkx.os.fleet.app.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

/**
 * 运行器引导测试：
 * <ul>
 * <li>阶段 4.2 start 子命令目标解析（mode→appId 推导、latest→已发布版本、--app-id/--version
 * 覆盖）；</li>
 * <li>阶段 4.3 stop 子命令：端到端（start 独立 JVM 写 pidfile → stop 独立 JVM 按 PID 停）、
 * stopCommand 路径、失败场景（registry 无记录 / descriptor 缺失 / 无 PID 无 stopCommand /
 * --pid 非法）；</li>
 * <li>阶段 4.4 switch 子命令：端到端、回滚、update.lock 冲突、失败场景、等旧进程退出超时，
 * 以及 writeConfigStartupVersion / ensureRegistryLatest / cleanupOldVersions
 * 纯函数测试。</li>
 * </ul>
 */
public class AppRunnerBootTest {

    /** 阶段 4.2：start 目标解析（mode→appId 推导 + latest→已发布版本 + 覆盖参数 + 无记录抛异常）。 */
    @Test
    public void testResolveStartTarget() throws Exception {
        Path home = Files.createTempDirectory("boot-start-home");
        Path appstore = home.resolve("appstore");
        Files.createDirectories(appstore);
        // 每条目均含 appId/version/publishedAt（BootConfig 极简正则要求字段齐全）
        Files
                .write(appstore.resolve("registry.json"),
                        ("[{\"entryId\":\"dist-server-1\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.1.0\",\"publishedAt\":1},"
                                + "{\"entryId\":\"dist-server-2\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.2.0\",\"publishedAt\":2},"
                                + "{\"entryId\":\"dist-client\",\"appId\":\"arkxos-fleet-client\",\"version\":\"0.5.0\",\"publishedAt\":3}]")
                                .getBytes(StandardCharsets.UTF_8));
        Files
                .write(home.resolve("config.yml"),
                        ("startup:\n  mode: server\n  version: latest\n").getBytes(StandardCharsets.UTF_8));

        // 默认：mode=server → appId=arkxos-fleet-server；version=latest → publishedAt 最大条目
        AppRunnerBoot.StartTarget target = AppRunnerBoot.resolveStartTarget(BootConfig.load(home), null, null);
        assertEquals("arkxos-fleet-server", target.appId);
        assertEquals("0.2.0", target.version);

        // mode=client → appId=arkxos-fleet-client
        Files
                .write(home.resolve("config.yml"),
                        ("startup:\n  mode: client\n  version: latest\n").getBytes(StandardCharsets.UTF_8));
        AppRunnerBoot.StartTarget client = AppRunnerBoot.resolveStartTarget(BootConfig.load(home), null, null);
        assertEquals("arkxos-fleet-client", client.appId);
        assertEquals("0.5.0", client.version);

        // --app-id 覆盖推导
        AppRunnerBoot.StartTarget overrideApp = AppRunnerBoot
                .resolveStartTarget(BootConfig.load(home), "arkxos-fleet-server", null);
        assertEquals("arkxos-fleet-server", overrideApp.appId);
        assertEquals("0.2.0", overrideApp.version);

        // --version 覆盖 latest（固定版本直接返回）
        AppRunnerBoot.StartTarget overrideVer = AppRunnerBoot.resolveStartTarget(BootConfig.load(home), null, "0.1.0");
        assertEquals("0.1.0", overrideVer.version);

        // registry 中无该 appId 记录且 version=latest → 抛 IllegalStateException
        Files.write(appstore.resolve("registry.json"), "[]".getBytes(StandardCharsets.UTF_8));
        BootConfig emptyConfig = BootConfig.load(home);
        try {
            AppRunnerBoot.resolveStartTarget(emptyConfig, "arkxos-fleet-switcher", "latest");
            fail("registry 无记录时应抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            // 预期异常
        }
    }

    /**
     * 阶段 4.3：stop 端到端 —— start（独立 JVM）写 pidfile → stop（独立 JVM）读 pidfile 按 PID 停。
     */
    @Test
    public void testRunStopByPid() throws Exception {
        Path home = Files.createTempDirectory("boot-stop-home");
        Path appstore = home.resolve("appstore");
        Files.createDirectories(appstore);
        Files
                .write(appstore.resolve("registry.json"),
                        "[{\"entryId\":\"dist-server-1\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.1.0\",\"publishedAt\":1}]"
                                .getBytes(StandardCharsets.UTF_8));
        Files
                .write(home.resolve("config.yml"),
                        ("startup:\n  mode: server\n  version: latest\napp:\n  maven-repo-path: \"\"\n")
                                .getBytes(StandardCharsets.UTF_8));

        Path appHome = home.resolve("appstore").resolve("installed").resolve("arkxos-fleet-server-0.1.0");
        Files.createDirectories(appHome);
        Files.copy(buildSleeperJar(home), appHome.resolve("sleeper.jar"));
        // {app.home} 在 resolve 阶段展开；AppInfo 自 minimal-json 改造后已支持转义引号/反斜杠
        // （此前手写正则解析不支持，带空格路径加引号会解析失败）
        Files
                .write(appHome.resolve("app-info.json"),
                        ("{\"name\":\"arkxos-fleet-server\",\"version\":\"0.1.0\",\"type\":\"ARK_SLIM\","
                                + "\"artifactId\":\"arkxos-fleet-server\",\"mainClass\":\"Sleeper\",\"jdkVersion\":\"25\","
                                + "\"launchCommand\":\"java -cp {app.home}/sleeper.jar Sleeper\",\"stopCommand\":null}")
                                .getBytes(StandardCharsets.UTF_8));

        // 1) start：独立 JVM，cwd=home → 写 pidfile、健康检查通过、交棒退出
        String startOut = runBoot(home, "start");
        assertTrue(startOut.contains("启动成功"), "start 应打印启动成功: " + startOut);
        Path pidFile = appHome.resolve(".runtime").resolve("app.pid");
        assertTrue(Files.isRegularFile(pidFile), "start 应写 .runtime/app.pid");
        long pid = Long.parseLong(new String(Files.readAllBytes(pidFile), StandardCharsets.UTF_8).trim());
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "子进程应存活");

        // 2) stop：独立 JVM，cwd=home，不传 --pid → 读 pidfile 按 PID 停
        String stopOut = runBoot(home, "stop");
        assertTrue(stopOut.contains("已停止 PID=" + pid), "stop 应打印已停止: " + stopOut);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "子进程应已停止");
        assertFalse(Files.exists(pidFile), "pidfile 应已删除");
    }

    /** 阶段 4.3：stopCommand 路径 —— 有 stopCommand 时不依赖 PID，执行 stopCommand 即成功。 */
    @Test
    public void testRunStopWithStopCommand() throws Exception {
        Path home = Files.createTempDirectory("boot-stopcmd-home");
        Path appstore = home.resolve("appstore");
        Files.createDirectories(appstore);
        Files
                .write(appstore.resolve("registry.json"),
                        "[{\"entryId\":\"d1\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.1.0\",\"publishedAt\":1}]"
                                .getBytes(StandardCharsets.UTF_8));
        Files
                .write(home.resolve("config.yml"),
                        ("startup:\n  mode: server\n  version: latest\n").getBytes(StandardCharsets.UTF_8));

        Path appHome = home.resolve("appstore").resolve("installed").resolve("arkxos-fleet-server-0.1.0");
        Files.createDirectories(appHome);
        // stopCommand 用 {app.home} 占位符（AppInfo 解析不支持转义引号/反斜杠；resolve 阶段展开为绝对路径）
        Files
                .write(appHome.resolve("app-info.json"),
                        ("{\"name\":\"arkxos-fleet-server\",\"version\":\"0.1.0\",\"type\":\"BASIC\","
                                + "\"artifactId\":\"arkxos-fleet-server\",\"mainClass\":\"\",\"jdkVersion\":\"\","
                                + "\"launchCommand\":\"echo never\",\"stopCommand\":\"cmd /c echo ok > {app.home}/stopped.mark\"}")
                                .getBytes(StandardCharsets.UTF_8));

        int code = AppRunnerBoot.runStopAt(home, null, null, null);
        assertEquals(0, code, "stopCommand 路径应返回 0");
        // cmd.exe 子进程为异步执行，轮询等待 mark 落盘
        Path mark = appHome.resolve("stopped.mark");
        long deadline = System.currentTimeMillis() + 10_000;
        while (!Files.exists(mark) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(Files.exists(mark), "stopCommand 应被执行");
        assertEquals("ok", new String(Files.readAllBytes(mark), StandardCharsets.UTF_8).trim());
    }

    /**
     * 阶段 4.3：失败场景 → 返回非 0（registry 无记录 / descriptor 缺失 / 无 PID 无 stopCommand /
     * --pid 非法）。
     */
    @Test
    public void testRunStopFailures() throws Exception {
        Path home = Files.createTempDirectory("boot-stop-fail");
        Path appstore = home.resolve("appstore");
        Files.createDirectories(appstore);
        Files
                .write(home.resolve("config.yml"),
                        ("startup:\n  mode: server\n  version: latest\n").getBytes(StandardCharsets.UTF_8));

        // 1) registry 无记录 → 1
        Files.write(appstore.resolve("registry.json"), "[]".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, AppRunnerBoot.runStopAt(home, null, null, null), "registry 无记录应返回非 0");

        // 2) descriptor 缺失 → 1
        Files
                .write(appstore.resolve("registry.json"),
                        "[{\"entryId\":\"d1\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.1.0\",\"publishedAt\":1}]"
                                .getBytes(StandardCharsets.UTF_8));
        assertEquals(1, AppRunnerBoot.runStopAt(home, null, null, null), "descriptor 缺失应返回非 0");

        // 3) 无 PID 且无 stopCommand → 1
        Path appHome = home.resolve("appstore").resolve("installed").resolve("arkxos-fleet-server-0.1.0");
        Files.createDirectories(appHome);
        Files
                .write(appHome.resolve("app-info.json"),
                        ("{\"name\":\"arkxos-fleet-server\",\"version\":\"0.1.0\",\"type\":\"ARK_SLIM\","
                                + "\"artifactId\":\"arkxos-fleet-server\",\"mainClass\":\"Sleeper\",\"jdkVersion\":\"25\","
                                + "\"launchCommand\":\"java -version\",\"stopCommand\":null}")
                                .getBytes(StandardCharsets.UTF_8));
        assertEquals(1, AppRunnerBoot.runStopAt(home, null, null, null), "无 PID 且无 stopCommand 应返回非 0");

        // 4) --pid 非法 → 1
        assertEquals(1, AppRunnerBoot.runStopAt(home, null, null, "abc"), "--pid 非法应返回非 0");
    }

    /**
     * 阶段 4.4：switch 端到端 —— start 起旧版本（独立 JVM 写 pidfile）→ switch 切到新版本 →
     * 旧进程退出、config 回写。
     */
    @Test
    public void testRunSwitchSuccess() throws Exception {
        Path home = Files.createTempDirectory("boot-switch-home");
        Path appstore = home.resolve("appstore");
        Files.createDirectories(appstore);
        Files
                .write(appstore.resolve("registry.json"),
                        ("[{\"entryId\":\"a\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.1.0\",\"publishedAt\":1},"
                                + "{\"entryId\":\"b\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.2.0\",\"publishedAt\":2}]")
                                .getBytes(StandardCharsets.UTF_8));
        Files
                .write(home.resolve("config.yml"),
                        ("startup:\n  mode: server\n  version: 0.1.0\napp:\n  maven-repo-path: \"\"\n")
                                .getBytes(StandardCharsets.UTF_8));

        // 两个版本目录：各放 sleeper.jar + descriptor（launchCommand 无引号/反斜杠）
        Path v1 = appstore.resolve("installed").resolve("arkxos-fleet-server-0.1.0");
        Path v2 = appstore.resolve("installed").resolve("arkxos-fleet-server-0.2.0");
        for (Path appHome : new Path[]{v1, v2}) {
            Files.createDirectories(appHome);
            Files.copy(buildSleeperJar(home), appHome.resolve("sleeper.jar"));
            String ver = appHome.getFileName().toString().substring("arkxos-fleet-server-".length());
            Files
                    .write(appHome.resolve("app-info.json"),
                            ("{\"name\":\"arkxos-fleet-server\",\"version\":\"" + ver + "\",\"type\":\"ARK_SLIM\","
                                    + "\"artifactId\":\"arkxos-fleet-server\",\"mainClass\":\"Sleeper\",\"jdkVersion\":\"25\","
                                    + "\"launchCommand\":\"java -cp {app.home}/sleeper.jar Sleeper\",\"stopCommand\":null}")
                                    .getBytes(StandardCharsets.UTF_8));
        }

        // 1) start 起旧版本 0.1.0（--version 显式，config 不回写）
        String startOut = runBoot(home, "start", "--version", "0.1.0");
        assertTrue(startOut.contains("启动成功"), "start 应打印启动成功: " + startOut);
        Path oldPidFile = v1.resolve(".runtime").resolve("app.pid");
        assertTrue(Files.isRegularFile(oldPidFile), "旧版本应写 pidfile");
        long oldPid = Long.parseLong(new String(Files.readAllBytes(oldPidFile), StandardCharsets.UTF_8).trim());

        // 2) switch 到 0.2.0（--old-pid 缺省读旧版本 pidfile）
        int code = AppRunnerBoot.runSwitchAt(home, null, "0.2.0", null, 5000, 1000, 3);
        assertEquals(0, code, "switch 应返回 0");
        assertFalse(ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false), "旧版本进程应已停止");
        Path newPidFile = v2.resolve(".runtime").resolve("app.pid");
        assertTrue(Files.isRegularFile(newPidFile), "新版本应写 pidfile");
        long newPid = Long.parseLong(new String(Files.readAllBytes(newPidFile), StandardCharsets.UTF_8).trim());
        assertTrue(ProcessHandle.of(newPid).map(ProcessHandle::isAlive).orElse(false), "新版本进程应存活");
        assertTrue(
                new String(Files.readAllBytes(home.resolve("config.yml")), StandardCharsets.UTF_8)
                        .contains("version: 0.2.0"),
                "config.yml startup.version 应回写为 0.2.0");
        assertFalse(Files.exists(appstore.resolve("update.lock")), "update.lock 应被删除");

        // 清理：stop（startup.version=0.2.0 → 停新版本）
        assertEquals(0, AppRunnerBoot.runStopAt(home, null, null, null), "清理 stop 应返回 0");
        assertFalse(ProcessHandle.of(newPid).map(ProcessHandle::isAlive).orElse(false), "新版本进程应已清理");
    }

    /** 阶段 4.4：回滚 —— 新版本起不来（健康检查失败）→ 重启旧版本、退出非 0、指针未回写。 */
    @Test
    public void testRunSwitchRollback() throws Exception {
        Path home = Files.createTempDirectory("boot-switch-rollback");
        Path appstore = home.resolve("appstore");
        Files.createDirectories(appstore);
        Files
                .write(appstore.resolve("registry.json"),
                        ("[{\"entryId\":\"a\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.1.0\",\"publishedAt\":1},"
                                + "{\"entryId\":\"b\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.2.0\",\"publishedAt\":2}]")
                                .getBytes(StandardCharsets.UTF_8));
        Files
                .write(home.resolve("config.yml"),
                        ("startup:\n  mode: server\n  version: 0.1.0\napp:\n  maven-repo-path: \"\"\n")
                                .getBytes(StandardCharsets.UTF_8));

        Path v1 = appstore.resolve("installed").resolve("arkxos-fleet-server-0.1.0");
        Path v2 = appstore.resolve("installed").resolve("arkxos-fleet-server-0.2.0");
        Files.createDirectories(v1);
        Files.createDirectories(v2);
        Files.copy(buildSleeperJar(home), v1.resolve("sleeper.jar"));
        Files
                .write(v1.resolve("app-info.json"),
                        ("{\"name\":\"arkxos-fleet-server\",\"version\":\"0.1.0\",\"type\":\"ARK_SLIM\","
                                + "\"artifactId\":\"arkxos-fleet-server\",\"mainClass\":\"Sleeper\",\"jdkVersion\":\"25\","
                                + "\"launchCommand\":\"java -cp {app.home}/sleeper.jar Sleeper\",\"stopCommand\":null}")
                                .getBytes(StandardCharsets.UTF_8));
        // 新版本：launchCommand 指向不存在的 jar/类 → 进程启动后立即退出 → 健康检查失败
        Files
                .write(v2.resolve("app-info.json"),
                        ("{\"name\":\"arkxos-fleet-server\",\"version\":\"0.2.0\",\"type\":\"ARK_SLIM\","
                                + "\"artifactId\":\"arkxos-fleet-server\",\"mainClass\":\"Nope\",\"jdkVersion\":\"25\","
                                + "\"launchCommand\":\"java -cp {app.home}/nope.jar Nope\",\"stopCommand\":null}")
                                .getBytes(StandardCharsets.UTF_8));

        String startOut = runBoot(home, "start", "--version", "0.1.0");
        assertTrue(startOut.contains("启动成功"), "start 应打印启动成功: " + startOut);

        int code = AppRunnerBoot.runSwitchAt(home, null, "0.2.0", null, 5000, 1000, 3);
        assertEquals(1, code, "新版本起不来应返回非 0");
        Path oldPidFile = v1.resolve(".runtime").resolve("app.pid");
        assertTrue(Files.isRegularFile(oldPidFile), "回滚后旧版本应写 pidfile");
        long rollbackPid = Long.parseLong(new String(Files.readAllBytes(oldPidFile), StandardCharsets.UTF_8).trim());
        assertTrue(ProcessHandle.of(rollbackPid).map(ProcessHandle::isAlive).orElse(false), "回滚后旧版本进程应存活");
        // 指针未回写（切换失败）：config startup.version 仍是 0.1.0
        assertTrue(
                new String(Files.readAllBytes(home.resolve("config.yml")), StandardCharsets.UTF_8)
                        .contains("version: 0.1.0"),
                "config.yml startup.version 不应回写");
        assertFalse(Files.exists(appstore.resolve("update.lock")), "update.lock 应被删除");

        // 清理：stop（startup.version=0.1.0 → 停回滚后的旧版本）
        assertEquals(0, AppRunnerBoot.runStopAt(home, null, null, null), "清理 stop 应返回 0");
        assertFalse(ProcessHandle.of(rollbackPid).map(ProcessHandle::isAlive).orElse(false), "回滚进程应已清理");
    }

    /** 阶段 4.4/5.2：update.lock 已存在但内容不匹配（并发切换，非宿主自更新编排）→ 直接失败。 */
    @Test
    public void testRunSwitchLockConflict() throws Exception {
        Path home = Files.createTempDirectory("boot-switch-lock");
        Path appstore = home.resolve("appstore");
        Files.createDirectories(appstore);
        Files
                .write(appstore.resolve("registry.json"),
                        "[{\"entryId\":\"a\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.2.0\",\"publishedAt\":2}]"
                                .getBytes(StandardCharsets.UTF_8));
        Files
                .write(home.resolve("config.yml"),
                        ("startup:\n  mode: server\n  version: latest\n").getBytes(StandardCharsets.UTF_8));
        // 内容不匹配（appId=x 而非 arkxos-fleet-server）→ 视为并发切换冲突
        Files.write(appstore.resolve("update.lock"), "{\"appId\":\"x\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(1,
                AppRunnerBoot.runSwitchAt(home, null, "0.2.0", null, 5000, 1000, 3), "update.lock 内容不匹配应返回非 0");
        assertTrue(Files.exists(appstore.resolve("update.lock")), "lock 不应被删除");
    }

    /**
     * 阶段 5.2：宿主自更新编排先写 update.lock（内容匹配 appId/targetVersion）再拉脱离进程 boot switch。
     * boot 应「复用」宿主写的 lock（不重写 pid），继续执行到目标版本校验。
     * 此处目标 0.2.0 未安装 → 返回 1，且 lock 的 pid 保持宿主原值（证明未被 boot 重写）。
     */
    @Test
    public void testRunSwitchReusesMatchingLock() throws Exception {
        Path home = Files.createTempDirectory("boot-switch-reuse-lock");
        Path appstore = home.resolve("appstore");
        Files.createDirectories(appstore);
        Files
                .write(appstore.resolve("registry.json"),
                        "[{\"entryId\":\"a\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.2.0\",\"publishedAt\":2}]"
                                .getBytes(StandardCharsets.UTF_8));
        Files
                .write(home.resolve("config.yml"),
                        ("startup:\n  mode: server\n  version: latest\n").getBytes(StandardCharsets.UTF_8));
        // 宿主自更新编排写的 lock：appId/targetVersion 与本次 switch 目标一致，pid 为宿主假值
        Files
                .write(appstore.resolve("update.lock"),
                        "{\"appId\":\"arkxos-fleet-server\",\"targetVersion\":\"0.2.0\",\"pid\":99999}"
                                .getBytes(StandardCharsets.UTF_8));

        // 内容匹配 → boot 复用 lock，不因 lock 冲突提前失败；继续执行（目标未安装 → 返回 1）
        assertEquals(1,
                AppRunnerBoot.runSwitchAt(home, null, "0.2.0", null, 5000, 1000, 3), "复用匹配 lock 后应继续执行到目标校验并返回非 0");
        // lock 保留且 pid 未被 boot 重写（复用分支不重写）
        assertTrue(Files.exists(appstore.resolve("update.lock")), "匹配 lock 不应被删除");
        String content = new String(Files.readAllBytes(appstore.resolve("update.lock")), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"pid\":99999"), "复用 lock 的 pid 应保持宿主原值");
    }

    /** 阶段 4.4：失败场景 → 返回非 0（缺 --target-version / --old-pid 非法 / 目标版本未安装）。 */
    @Test
    public void testRunSwitchFailures() throws Exception {
        Path home = Files.createTempDirectory("boot-switch-fail");
        Path appstore = home.resolve("appstore");
        Files.createDirectories(appstore);
        Files
                .write(appstore.resolve("registry.json"),
                        "[{\"entryId\":\"a\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.2.0\",\"publishedAt\":2}]"
                                .getBytes(StandardCharsets.UTF_8));
        Files
                .write(home.resolve("config.yml"),
                        ("startup:\n  mode: server\n  version: latest\n").getBytes(StandardCharsets.UTF_8));

        // 1) 缺 --target-version → 1
        assertEquals(1, AppRunnerBoot.runSwitchAt(home, null, null, null, 5000, 1000, 3),
                "缺 --target-version 应返回非 0");
        // 2) --old-pid 非法 → 1
        assertEquals(1, AppRunnerBoot.runSwitchAt(home, null, "0.2.0", "abc", 5000, 1000, 3),
                "--old-pid 非法应返回非 0");
        // 3) 目标版本未安装（registry 有记录但缺目录/descriptor）→ 1
        assertEquals(1, AppRunnerBoot.runSwitchAt(home, null, "0.2.0", null, 5000, 1000, 3),
                "目标版本未安装应返回非 0");
    }

    /** 阶段 4.4：等旧进程退出超时 → 失败退出（stopCommand 不杀进程 + 旧 PID 存活）。 */
    @Test
    public void testRunSwitchWaitTimeout() throws Exception {
        Path home = Files.createTempDirectory("boot-switch-wait");
        Path appstore = home.resolve("appstore");
        Files.createDirectories(appstore);
        Files
                .write(appstore.resolve("registry.json"),
                        ("[{\"entryId\":\"a\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.1.0\",\"publishedAt\":1},"
                                + "{\"entryId\":\"b\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.2.0\",\"publishedAt\":2}]")
                                .getBytes(StandardCharsets.UTF_8));
        Files
                .write(home.resolve("config.yml"),
                        ("startup:\n  mode: server\n  version: 0.1.0\napp:\n  maven-repo-path: \"\"\n")
                                .getBytes(StandardCharsets.UTF_8));

        Path v1 = appstore.resolve("installed").resolve("arkxos-fleet-server-0.1.0");
        Path v2 = appstore.resolve("installed").resolve("arkxos-fleet-server-0.2.0");
        Files.createDirectories(v1);
        Files.createDirectories(v2);
        Files.copy(buildSleeperJar(home), v1.resolve("sleeper.jar"));
        Files
                .write(v1.resolve("app-info.json"),
                        ("{\"name\":\"arkxos-fleet-server\",\"version\":\"0.1.0\",\"type\":\"BASIC\","
                                + "\"artifactId\":\"arkxos-fleet-server\",\"mainClass\":\"Sleeper\",\"jdkVersion\":\"25\","
                                + "\"launchCommand\":\"java -cp {app.home}/sleeper.jar Sleeper\","
                                + "\"stopCommand\":\"cmd /c echo ok > {app.home}/stopped.mark\"}")
                                .getBytes(StandardCharsets.UTF_8));
        Files
                .write(v2.resolve("app-info.json"),
                        ("{\"name\":\"arkxos-fleet-server\",\"version\":\"0.2.0\",\"type\":\"ARK_SLIM\","
                                + "\"artifactId\":\"arkxos-fleet-server\",\"mainClass\":\"Sleeper\",\"jdkVersion\":\"25\","
                                + "\"launchCommand\":\"java -cp {app.home}/sleeper.jar Sleeper\",\"stopCommand\":null}")
                                .getBytes(StandardCharsets.UTF_8));

        // 起旧版本（pidfile 指向存活的 Sleeper）
        String startOut = runBoot(home, "start", "--version", "0.1.0");
        assertTrue(startOut.contains("启动成功"), "start 应打印启动成功: " + startOut);
        long oldPid = Long
                .parseLong(new String(Files
                        .readAllBytes(
                                v1.resolve(".runtime").resolve("app.pid")),
                        StandardCharsets.UTF_8).trim());

        // stopCommand 不杀进程（cmd echo），等待 --wait=300ms 后超时
        int code = AppRunnerBoot.runSwitchAt(home, null, "0.2.0", null, 300, 500, 3);
        assertEquals(1, code, "等旧进程退出超时应返回非 0");
        assertTrue(Files.exists(v1.resolve("stopped.mark")),
                "stopCommand 应被执行");
        assertTrue(ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false), "旧进程应仍存活（未被停止）");

        // 清理：0.1.0 有 stopCommand（不杀进程），runStopAt 只会执行它 → 直接 destroy PID 并等退出
        ProcessHandle.of(oldPid).ifPresent(ProcessHandle::destroy);
        long deadline = System.currentTimeMillis() + 10_000;
        while (ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertFalse(ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false), "旧进程应已清理");
    }

    /**
     * 阶段 4.4：config.yml startup.version 回写 —— 已有 version 行替换 / 段内插入 / 末尾追加 / 文件缺失。
     */
    @Test
    public void testWriteConfigStartupVersion() throws Exception {
        Path f = Files.createTempFile("boot-cfg", ".yml");
        try {
            // 1) 已有 version 行 → 原位替换
            Files.writeString(f, "startup:\n  mode: server\n  version: 0.1.0\napp:\n  x: 1\n");
            assertTrue(AppRunnerBoot.writeConfigStartupVersion(f, "0.2.0"));
            String c = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            assertTrue(c.contains("version: 0.2.0"), "version 应被替换: " + c);
            assertFalse(c.contains("version: 0.1.0"), "旧版本不应残留: " + c);

            // 2) 有 startup 段但无 version 行 → 段尾插入
            Files.writeString(f, "startup:\n  mode: client\napp:\n  x: 1\n");
            assertTrue(AppRunnerBoot.writeConfigStartupVersion(f, "0.5.0"));
            c = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            assertTrue(c.contains("version: 0.5.0"), "应插入 version 行: " + c);
            assertTrue(c.indexOf("version: 0.5.0") < c.indexOf("app:"),
                    "version 应位于 startup 段内(app: 之前): " + c);

            // 3) 无 startup 段 → 文件末尾追加
            Files.writeString(f, "app:\n  maven-repo-path: \"\"\n");
            assertTrue(AppRunnerBoot.writeConfigStartupVersion(f, "0.9.0"));
            c = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            assertTrue(c.contains("startup:\n  version: 0.9.0"), "应追加 startup 段: " + c);

            // 4) config 不存在 → false
            assertFalse(AppRunnerBoot.writeConfigStartupVersion(f.resolveSibling("missing.yml"), "1.0"));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    /** 阶段 4.4：registry.latest 回写 —— 已最大则不动 / 非最大则提升 / 目标不在 registry → false。 */
    @Test
    public void testEnsureRegistryLatest() throws Exception {
        Path reg = Files.createTempFile("boot-reg", ".json");
        try {
            String base = "[{\"entryId\":\"a\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.1.0\",\"publishedAt\":1},"
                    + "{\"entryId\":\"b\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.2.0\",\"publishedAt\":2}]";
            Files.writeString(reg, base);
            // 1) 已是最大 → true 且内容不变
            assertTrue(AppRunnerBoot.ensureRegistryLatest(reg, "arkxos-fleet-server", "0.2.0"));
            assertEquals(base, new String(Files.readAllBytes(reg), StandardCharsets.UTF_8));

            // 2) 非最大（异常：目标 publishedAt 小于另一条目）→ 提升为 max+1
            String smaller = "[{\"entryId\":\"a\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.1.0\",\"publishedAt\":5},"
                    + "{\"entryId\":\"b\",\"appId\":\"arkxos-fleet-server\",\"version\":\"0.2.0\",\"publishedAt\":2}]";
            Files.writeString(reg, smaller);
            assertTrue(AppRunnerBoot.ensureRegistryLatest(reg, "arkxos-fleet-server", "0.2.0"));
            String c = new String(Files.readAllBytes(reg), StandardCharsets.UTF_8);
            assertTrue(c.contains("\"version\":\"0.2.0\",\"publishedAt\":6"), "目标版本应提升为 latest: " + c);

            // 3) 目标版本不在 registry → false
            assertFalse(AppRunnerBoot.ensureRegistryLatest(reg, "arkxos-fleet-server", "9.9.9"));
        } finally {
            Files.deleteIfExists(reg);
        }
    }

    /** 阶段 4.4：--keep 清理 —— 保留版本号最大的 keep 个 + 目标版本目录（防降级切换误删）。 */
    @Test
    public void testCleanupOldVersions() throws Exception {
        Path installed = Files.createTempDirectory("boot-cleanup").resolve("installed");
        Files.createDirectories(installed);
        for (String v : new String[]{"0.1.0", "0.2.0", "0.3.0"}) {
            Path d = installed.resolve("arkxos-fleet-server-" + v);
            Files.createDirectories(d);
            Files.writeString(d.resolve("app.jar"), "x");
        }
        Files.createDirectories(installed.resolve("arkxos-fleet-client-0.5.0")); // 其他 appId 不受影响

        // keep=2 → 删除版本最低的 0.1.0
        AppRunnerBoot.cleanupOldVersions(installed, "arkxos-fleet-server", "0.3.0", 2);
        assertFalse(Files.exists(installed.resolve("arkxos-fleet-server-0.1.0")), "0.1.0 应被清理");
        assertTrue(Files.exists(installed.resolve("arkxos-fleet-server-0.2.0")), "0.2.0 应保留");
        assertTrue(Files.exists(installed.resolve("arkxos-fleet-server-0.3.0")), "0.3.0 应保留");
        assertTrue(Files.exists(installed.resolve("arkxos-fleet-client-0.5.0")), "其他 appId 不应被清理");

        // 降级切换：keepVersion=0.1.0（本会被删）→ 目标目录必留
        Files.createDirectories(installed.resolve("arkxos-fleet-server-0.1.0"));
        Files.writeString(installed.resolve("arkxos-fleet-server-0.1.0").resolve("app.jar"), "x");
        AppRunnerBoot.cleanupOldVersions(installed, "arkxos-fleet-server", "0.1.0", 2);
        assertTrue(Files.exists(installed.resolve("arkxos-fleet-server-0.1.0")), "降级切换目标 0.1.0 应保留");
        assertTrue(Files.exists(installed.resolve("arkxos-fleet-server-0.2.0")), "0.2.0 应保留");
        assertTrue(Files.exists(installed.resolve("arkxos-fleet-server-0.3.0")), "0.3.0 应保留");

        // keep=0 → 不清理
        AppRunnerBoot.cleanupOldVersions(installed, "arkxos-fleet-server", "0.3.0", 0);
        assertTrue(Files.exists(installed.resolve("arkxos-fleet-server-0.1.0")), "keep=0 不应清理");
    }

    /** 独立 JVM 跑 AppRunnerBoot（cwd=home，隔离 user.dir），断言退出码 0 并返回合并输出。 */
    private String runBoot(Path home, String... args) throws Exception {
        String javaExe = Path
                .of(System.getProperty("java.home"), "bin",
                        isWindows() ? "java.exe" : "java")
                .toString();
        String cp = System.getProperty("java.class.path");
        // stdout.encoding=UTF-8：Windows 控制台默认 GBK，避免独立 JVM 中文输出经管道被误读
        String[] cmd = new String[args.length + 5];
        cmd[0] = javaExe;
        cmd[1] = "-Dstdout.encoding=UTF-8";
        cmd[2] = "-cp";
        cmd[3] = cp;
        cmd[4] = AppRunnerBoot.class.getName();
        System.arraycopy(args, 0, cmd, 5, args.length);
        Process proc = new ProcessBuilder(cmd)
                .directory(home.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        System.out.println("[boot-test] " + out.replace("\n", "\n[boot-test] "));
        assertEquals(0, proc.waitFor(), "boot 进程退出码应为 0:\n" + out);
        return out;
    }

    /** 编译 {@code Sleeper}（休眠 1 小时）并打成 jar，作为可启动、可停的假应用。 */
    private Path buildSleeperJar(Path home) throws IOException {
        String src = """
                public class Sleeper {
                  public static void main(String[] args) throws Exception {
                    System.out.println("sleeper up");
                    Thread.sleep(3_600_000L);
                  }
                }
                """;
        Path srcFile = home.resolve("src").resolve("Sleeper.java");
        Files.createDirectories(srcFile.getParent());
        Files.write(srcFile, src.getBytes(StandardCharsets.UTF_8));

        Path classes = home.resolve("classes-sleeper");
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "本测试需要 JDK（含 javac）");
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Boolean ok = compiler
                    .getTask(null, fm, null,
                            List.of("-d", classes.toAbsolutePath().toString(), "--release", "25"),
                            null, fm.getJavaFileObjects(srcFile.toFile()))
                    .call();
            assertTrue(Boolean.TRUE.equals(ok), "Sleeper 编译失败");
        }

        Path jar = home.resolve("sleeper.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
            addToJar(jos, classes.resolve("Sleeper.class"), "Sleeper.class");
        }
        return jar;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void addToJar(JarOutputStream jos, Path file, String entryName) throws IOException {
        jos.putNextEntry(new JarEntry(entryName));
        jos.write(Files.readAllBytes(file));
        jos.closeEntry();
    }
}
