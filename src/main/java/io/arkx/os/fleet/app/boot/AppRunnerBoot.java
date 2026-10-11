package io.arkx.os.fleet.app.boot;

import io.arkx.os.fleet.app.bundle.AppInfo;
import io.arkx.os.fleet.app.command.CommandCore;
import io.arkx.os.fleet.app.command.StartedProcess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 运行器引导器（arkxos-fleet-boot，唯一入口，永远不变，只做本地引导）。
 *
 * <p>
 * BOOT-REDESIGN（resources/design/BOOT-REDESIGN.md）：
 * </p>
 * <ul>
 * <li>阶段 4.2：实现 {@code start} 子命令 —— 读 config → 推导 appId/version →
 * 定位 {@code app-info.json} → {@link CommandCore#start} 替换启动（内建健康检查）→
 * 打印 PID → 退出交棒（子进程由 OS 接管），启动成功后写 {@code .runtime/app.pid}（供 stop/switch
 * 定位）；</li>
 * <li>阶段 4.3：实现 {@code stop} 子命令 —— 定位 descriptor → 执行 stopCommand /
 * 按 PID 停止（PID 来源：--pid 显式 &gt; 启动时写入的 .runtime/app.pid），幂等（进程未运行也成功）；</li>
 * <li>阶段 4.4（本阶段）：实现 {@code switch} 子命令 —— 校验 update.lock（防并发切换）→ 停旧
 * （stopCommand / 按 PID，--old-pid 缺省读旧版本 .runtime/app.pid）→ 等旧进程退出（--wait 超时失败）→
 * 起新版本（同 start 代码路径，--health 自定义健康检查窗口）→ 成功回写 registry.latest +
 * config.yml startup.version；起新失败主动回滚旧版本 → 清理 update.lock、按 --keep
 * 清理旧版本目录；</li>
 * <li>阶段 4.5（本阶段）：移除 {@code SwitcherRouter} 与 URLClassLoader 反射链 —— 引导链收敛为
 * start/stop/switch 三子命令，boot 编译期仅依赖 bundle + command-core（静态、可验证）；</li>
 * <li>无参默认 {@code start}（{@code java -jar boot.jar} 与
 * {@code java -jar boot.jar start} 等价）。</li>
 * </ul>
 *
 * <p>
 * 约束：boot 不直接运行应用，没有任何下载/网络能力——只做本地引导。
 * </p>
 */
public class AppRunnerBoot {

    private static final String APP_ID_SERVER = "arkxos-fleet-server";
    private static final String APP_ID_CLIENT = "arkxos-fleet-client";

    public static void main(String[] args) throws Exception {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        // 子命令分发：无参默认 start；首个非 "-" 参数视为子命令
        String subcommand = "start";
        if (!rest.isEmpty() && !rest.get(0).startsWith("-")) {
            subcommand = rest.remove(0);
        }

        switch (subcommand) {
            case "start" :
                System.exit(runStart(optionValue(rest, "--app-id"), optionValue(rest, "--version")));
                return;
            case "stop" :
                System
                        .exit(runStop(optionValue(rest, "--app-id"), optionValue(rest, "--version"),
                                optionValue(rest, "--pid")));
                return;
            case "switch" :
                System
                        .exit(runSwitch(optionValue(rest, "--app-id"), optionValue(rest, "--target-version"),
                                optionValue(rest, "--old-pid"), optionValue(rest, "--home"),
                                optionValue(rest, "--wait"), optionValue(rest, "--health"),
                                optionValue(rest, "--keep")));
                return;
            default :
                System.err.println("[AppRunnerBoot] 未知子命令: " + subcommand);
                System.exit(2);
        }
    }

    // ==================== start（阶段 4.2 新增） ====================

    /** start 启动目标：appId + 已解析的实际版本。 */
    static final class StartTarget {
        final String appId;
        final String version;

        StartTarget(String appId, String version) {
            this.appId = appId;
            this.version = version;
        }

        @Override
        public String toString() {
            return appId + "-" + version;
        }
    }

    /**
     * 解析 start 目标（纯函数，可单测，不启动进程）：
     * <ul>
     * <li>appId：--app-id 覆盖 &gt; config.startup.mode
     * 推导（server→arkxos-fleet-server，client→arkxos-fleet-client）；</li>
     * <li>version：--version 覆盖 &gt; config.startup.version；经 registry.json
     * 解析为实际版本（latest → 已发布的最大版本）。</li>
     * </ul>
     *
     * @throws IllegalStateException
     *             registry 中无该 appId 的版本记录
     */
    static StartTarget resolveStartTarget(BootConfig config, String appIdOverride, String versionOverride) {
        String appId = appIdOverride;
        if (appId == null || appId.isBlank()) {
            String mode = config.startupMode();
            appId = "client".equalsIgnoreCase(mode) ? APP_ID_CLIENT : APP_ID_SERVER;
        }
        String version = versionOverride == null || versionOverride.isBlank()
                ? config.startupVersion()
                : versionOverride;
        String resolved = config.resolveVersion(appId, version);
        if (resolved == null) {
            throw new IllegalStateException("registry.json 中找不到 " + appId + " 的版本记录");
        }
        return new StartTarget(appId, resolved);
    }

    /**
     * boot start：读 config → 解析目标 → 定位 app-info.json → {@link CommandCore#start}
     * 替换启动
     * → 健康检查（内建）→ 打印 PID → 退出交棒（JVM 退出不杀子进程，destroyOnExit 默认 false）。
     *
     * @return 进程退出码（0 成功）
     */
    static int runStart(String appIdOverride, String versionOverride) throws Exception {
        Path home = Path.of("").toAbsolutePath();
        BootConfig config = BootConfig.load(home);
        StartTarget target = resolveStartTarget(config, appIdOverride, versionOverride);

        Path appHome = home.resolve("appstore").resolve("installed").resolve(target.toString());
        Path descriptorFile = appHome.resolve("app-info.json");
        if (!Files.isRegularFile(descriptorFile)) {
            System.err.println("[AppRunnerBoot] start 失败：缺少 app-info.json: " + descriptorFile);
            return 1;
        }
        AppInfo info = AppInfo.load(descriptorFile);
        StartedProcess process = CommandCore.start(info, appHome, Path.of(config.mavenRepoPath()), config.envVars());
        writePidFile(appHome, process.pid());
        System.out.println("[AppRunnerBoot] " + target + " 启动成功 PID=" + process.pid() + "（健康检查通过，交棒退出）");
        return 0;
    }

    /**
     * 启动成功后写入 PID 文件 {@code installed/{appId}-{v}/.runtime/app.pid}，供 stop/switch
     * 定位。
     */
    private static void writePidFile(Path appHome, long pid) {
        try {
            Path pidFile = appHome.resolve(".runtime").resolve("app.pid");
            Files.createDirectories(pidFile.getParent());
            Files.writeString(pidFile, String.valueOf(pid));
        } catch (IOException e) {
            System.err.println("[AppRunnerBoot] 警告：写入 PID 文件失败（stop 时需 --pid 显式指定）: " + e.getMessage());
        }
    }

    // ==================== stop（阶段 4.3 新增） ====================

    /**
     * boot stop：解析目标 → 定位 app-info.json → 停止。
     * <p>
     * 停止方式（与 {@link CommandCore#stop} 语义一致）：
     * <ul>
     * <li>descriptor 有 {@code stopCommand} → 执行 stopCommand（应用自管停止，不依赖 PID）；</li>
     * <li>否则按 PID 停止：--pid 显式 &gt; 启动时写入的 {@code .runtime/app.pid}。</li>
     * </ul>
     *
     * @return 进程退出码（0 成功；1 失败——目标无法解析 / descriptor 缺失 /
     *         无法定位 PID（无 --pid 无 pidfile 且无 stopCommand）/ --pid 非法 / 进程未能停止）
     */
    static int runStop(String appIdOverride, String versionOverride, String pidOverride) throws Exception {
        return runStopAt(Path.of("").toAbsolutePath(), appIdOverride, versionOverride, pidOverride);
    }

    /** runStop 的核心逻辑（home 注入，便于单测）。 */
    static int runStopAt(Path home, String appIdOverride, String versionOverride, String pidOverride) throws Exception {
        // --pid 显式参数必须为合法正整数
        long pidOverrideValue = parsePid(pidOverride);
        if (pidOverride != null && !pidOverride.isBlank() && pidOverrideValue <= 0) {
            System.err.println("[AppRunnerBoot] stop 失败：--pid 非法: " + pidOverride);
            return 1;
        }

        BootConfig config = BootConfig.load(home);
        StartTarget target;
        try {
            target = resolveStartTarget(config, appIdOverride, versionOverride);
        } catch (IllegalStateException e) {
            System.err.println("[AppRunnerBoot] stop 失败：" + e.getMessage());
            return 1;
        }

        Path appHome = home.resolve("appstore").resolve("installed").resolve(target.toString());
        Path descriptorFile = appHome.resolve("app-info.json");
        if (!Files.isRegularFile(descriptorFile)) {
            System.err.println("[AppRunnerBoot] stop 失败：缺少 app-info.json: " + descriptorFile);
            return 1;
        }
        AppInfo info = AppInfo.load(descriptorFile);

        // PID 决定：--pid > .runtime/app.pid（有 stopCommand 时不依赖 PID）
        long pid = pidOverrideValue;
        Path pidFile = appHome.resolve(".runtime").resolve("app.pid");
        if (pid <= 0 && Files.isRegularFile(pidFile)) {
            pid = parsePid(Files.readString(pidFile).trim());
        }

        boolean hasStopCommand = info.stopCommand != null && !info.stopCommand.isBlank();
        if (pid <= 0 && !hasStopCommand) {
            System.err
                    .println("[AppRunnerBoot] stop 失败：无可用 PID（未传 --pid 且无 .runtime/app.pid），"
                            + "且 descriptor 无 stopCommand: " + target);
            return 1;
        }

        boolean aliveBefore = pid > 0 && isAlive(pid);
        CommandCore.stop(info, pid, appHome, Path.of(config.mavenRepoPath()), config.envVars());

        if (hasStopCommand) {
            // stopCommand 由应用自管停止，无 PID 确认点；执行成功即视为已停止
            System.out.println("[AppRunnerBoot] " + target + " 已执行 stopCommand（应用自管停止）");
            deleteQuietly(pidFile);
            return 0;
        }
        boolean aliveAfter = isAlive(pid);
        if (!aliveBefore) {
            // 进程本就没跑（pidfile 残留或 --pid 目标已退出）→ 幂等成功
            System.out.println("[AppRunnerBoot] " + target + " 进程未运行（PID=" + pid + "，幂等成功）");
            deleteQuietly(pidFile);
            return 0;
        }
        if (aliveAfter) {
            System.err.println("[AppRunnerBoot] stop 失败：进程 PID=" + pid + " 未能停止: " + target);
            return 1;
        }
        System.out.println("[AppRunnerBoot] " + target + " 已停止 PID=" + pid);
        deleteQuietly(pidFile);
        return 0;
    }

    /** 解析 PID 参数：空/缺省 → 0；非法 → -1。 */
    private static long parsePid(String pid) {
        if (pid == null || pid.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(pid.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // 忽略：pidfile 删除失败不影响停止结果
        }
    }

    // ==================== switch（阶段 4.4 新增） ====================

    /**
     * boot switch：校验 update.lock（防并发切换）→ 停旧（stopCommand / 按 PID，--old-pid 缺省读旧版本
     * .runtime/app.pid）→ 等旧进程退出（--wait 超时则失败、指针未改）→ 起新版本（同 start 代码路径，
     * --health 自定义健康检查窗口）→ 成功回写 registry.latest + config.yml
     * startup.version（运行声明权威）；
     * 起新失败主动回滚旧版本目录 → 清理 update.lock、按 --keep 清理旧版本目录。
     *
     * @return 进程退出码（0 成功；1 失败——缺 --target-version / --old-pid 非法 / 目标未安装 /
     *         update.lock 已存在 / 等待旧进程退出超时 / 新版本启动失败（已尝试回滚））
     */
    static int runSwitch(String appIdOverride, String targetVersion, String oldPidOverride, String homeArg,
            String waitArg, String healthArg, String keepArg) throws Exception {
        long waitMs = parsePositiveLongArg(waitArg, 60_000L);
        long healthMs = parsePositiveLongArg(healthArg, 15_000L);
        int keep = parseNonNegativeIntArg(keepArg, 3);
        if (waitMs < 0 || healthMs < 0 || keep < 0) {
            System.err.println("[AppRunnerBoot] switch 失败：--wait/--health 需为正整数，--keep 需为非负整数");
            return 1;
        }
        Path home = homeArg != null && !homeArg.isBlank()
                ? Path.of(homeArg).toAbsolutePath()
                : Path.of("").toAbsolutePath();
        return runSwitchAt(home, appIdOverride, targetVersion, oldPidOverride, waitMs, healthMs, keep);
    }

    /** runSwitch 的核心逻辑（home 注入，便于单测）。 */
    static int runSwitchAt(Path home, String appIdOverride, String targetVersion, String oldPidOverride,
            long waitMs, long healthMs, int keep) throws Exception {
        if (targetVersion == null || targetVersion.isBlank()) {
            System.err.println("[AppRunnerBoot] switch 失败：缺少 --target-version");
            return 1;
        }
        long oldPid = parsePid(oldPidOverride);
        if (oldPidOverride != null && !oldPidOverride.isBlank() && oldPid <= 0) {
            System.err.println("[AppRunnerBoot] switch 失败：--old-pid 非法: " + oldPidOverride);
            return 1;
        }

        BootConfig config = BootConfig.load(home);
        StartTarget target;
        try {
            target = resolveStartTarget(config, appIdOverride, targetVersion);
        } catch (IllegalStateException e) {
            System.err.println("[AppRunnerBoot] switch 失败：" + e.getMessage());
            return 1;
        }

        Path newAppHome = home.resolve("appstore").resolve("installed").resolve(target.toString());
        Path newDescriptorFile = newAppHome.resolve("app-info.json");
        if (!Files.isRegularFile(newDescriptorFile)) {
            System.err.println("[AppRunnerBoot] switch 失败：目标版本未安装（缺少 app-info.json）: " + newDescriptorFile);
            return 1;
        }

        // 1) update.lock：防并发切换；流程结束（无论成败）删除。
        // 宿主自更新编排（BOOT-REDESIGN §9.3 链路2 / 阶段 5.2）会先写 update.lock（含目标版本）
        // 再拉脱离进程 boot switch：lock 已存在但 appId/targetVersion 与本次目标一致 → 复用继续（幂等）；
        // 内容不匹配 → 视为并发切换冲突失败。
        Path lockFile = home.resolve("appstore").resolve("update.lock");
        if (Files.exists(lockFile)) {
            String content;
            try {
                content = Files.readString(lockFile);
            } catch (IOException e) {
                content = "";
            }
            if (content.contains("\"appId\":\"" + target.appId + "\"")
                    && content.contains("\"targetVersion\":\"" + target.version + "\"")) {
                System.out.println("[AppRunnerBoot] 复用宿主自更新编排的 update.lock: " + lockFile);
            } else {
                System.err.println("[AppRunnerBoot] switch 失败：update.lock 已存在且内容不匹配，可能有切换正在执行: " + lockFile);
                return 1;
            }
        } else {
            Files.createDirectories(lockFile.getParent());
            Files
                    .writeString(lockFile, "{\"appId\":\"" + target.appId + "\",\"targetVersion\":\""
                            + target.version + "\",\"pid\":" + ProcessHandle.current().pid() + "}");
        }

        try {
            return doSwitch(config, home, target, newAppHome, oldPid, waitMs, healthMs, keep);
        } finally {
            deleteQuietly(lockFile);
        }
    }

    /** switch 主流程（update.lock 已创建，结束时由调用方 finally 删除）。 */
    private static int doSwitch(BootConfig config, Path home, StartTarget target, Path newAppHome,
            long oldPid, long waitMs, long healthMs, int keep) throws Exception {
        Path m2 = Path.of(config.mavenRepoPath());
        Map<String, String> env = config.envVars();
        Path installedDir = home.resolve("appstore").resolve("installed");

        // 2) 当前运行版本（回滚目标）：存活进程对应版本 > 运行声明（registry.latest / startup.version）兜底
        String oldVersion = findRunningVersion(installedDir, target.appId);
        if (oldVersion == null) {
            try {
                oldVersion = resolveStartTarget(config, target.appId, config.startupVersion()).version;
            } catch (IllegalStateException e) {
                oldVersion = null; // registry 无该 appId 记录 → 首次切换，无旧版本
            }
        }
        Path oldAppHome = oldVersion != null ? installedDir.resolve(target.appId + "-" + oldVersion) : null;

        // 3) 停旧（旧版本目录存在才停；stopCommand 优先，否则按 PID）
        if (oldAppHome != null && Files.isRegularFile(oldAppHome.resolve("app-info.json"))) {
            Path oldPidFile = oldAppHome.resolve(".runtime").resolve("app.pid");
            long pid = oldPid;
            if (pid <= 0 && Files.isRegularFile(oldPidFile)) {
                pid = parsePid(Files.readString(oldPidFile).trim());
            }
            AppInfo oldInfo = AppInfo.load(oldAppHome.resolve("app-info.json"));
            CommandCore.stop(oldInfo, pid, oldAppHome, m2, env);
            // 4) 等待旧进程退出（stopCommand 场景无 PID 则不等待，由应用自管）
            if (pid > 0 && isAlive(pid)) {
                long deadline = System.currentTimeMillis() + waitMs;
                while (System.currentTimeMillis() < deadline && isAlive(pid)) {
                    Thread.sleep(100);
                }
                if (isAlive(pid)) {
                    System.err
                            .println("[AppRunnerBoot] switch 失败：等待旧进程(PID=" + pid
                                    + ")退出超时(" + waitMs + "ms)，指针未改，下次引导仍起旧版本");
                    return 1;
                }
            }
        } else if (oldVersion != null) {
            System.err.println("[AppRunnerBoot] 警告：旧版本目录缺失，跳过停旧: " + oldAppHome);
        }

        // 5) 起新（与 start 同一条代码路径，--health 自定义健康检查窗口）
        AppInfo newInfo = AppInfo.load(newAppHome.resolve("app-info.json"));
        StartedProcess proc;
        try {
            proc = CommandCore.start(newInfo, newAppHome, m2, env, healthMs);
        } catch (IOException e) {
            // 6) 回滚：旧版本目录仍在则重启旧版本（再失败退出非 0；指针未改，懒回滚兜底）
            if (oldAppHome != null && !oldVersion.equals(target.version)
                    && Files.isRegularFile(oldAppHome.resolve("app-info.json"))) {
                try {
                    AppInfo oldInfo = AppInfo.load(oldAppHome.resolve("app-info.json"));
                    StartedProcess oldProc = CommandCore.start(oldInfo, oldAppHome, m2, env, healthMs);
                    writePidFile(oldAppHome, oldProc.pid());
                    System.out.println("[AppRunnerBoot] 已回滚到旧版本 " + oldVersion + " PID=" + oldProc.pid());
                } catch (IOException re) {
                    System.err.println("[AppRunnerBoot] 回滚失败：" + re.getMessage());
                }
            }
            System.err.println("[AppRunnerBoot] switch 失败：新版本启动失败: " + e.getMessage());
            return 1;
        }
        writePidFile(newAppHome, proc.pid());
        System.out.println("[AppRunnerBoot] " + target + " 切换成功 PID=" + proc.pid());

        // 7) 回写运行声明：config.yml startup.version（权威）+ registry.latest（publishedAt 兜底）
        boolean cfgOk = writeConfigStartupVersion(home.resolve("config.yml"), target.version);
        boolean regOk = ensureRegistryLatest(home.resolve("appstore").resolve("registry.json"),
                target.appId, target.version);
        if (!cfgOk) {
            System.err
                    .println("[AppRunnerBoot] 警告：config.yml startup.version 回写失败"
                            + "（下次 boot start 可能仍起旧版本）");
        }
        if (!regOk) {
            System.err
                    .println("[AppRunnerBoot] 警告：registry.latest 回写失败"
                            + "（下次 boot start 默认版本可能不是 " + target.version + "）");
        }

        // 8) 按 --keep 清理旧版本目录（切换成功，回滚不再需要；目标版本必留，防降级切换误删）
        if (keep > 0) {
            cleanupOldVersions(installedDir, target.appId, target.version, keep);
        }
        return 0;
    }

    /** 解析 --wait/--health：空/缺省 → 默认；非法或非正 → -1。 */
    private static long parsePositiveLongArg(String s, long def) {
        if (s == null || s.isBlank())
            return def;
        try {
            long v = Long.parseLong(s.trim());
            return v > 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 解析 --keep：空/缺省 → 默认；非法或负数 → -1。 */
    private static int parseNonNegativeIntArg(String s, int def) {
        if (s == null || s.isBlank())
            return def;
        try {
            int v = Integer.parseInt(s.trim());
            return v >= 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 推导当前运行版本（回滚目标）：遍历 installed/{appId}-* 的 .runtime/app.pid，
     * 取 PID 存活且版本号最大的目录；无存活进程返回 null。
     */
    static String findRunningVersion(Path installedDir, String appId) {
        if (!Files.isDirectory(installedDir))
            return null;
        try (Stream<Path> list = Files.list(installedDir)) {
            List<Path> dirs = list
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(appId + "-"))
                    .toList();
            Path best = null;
            for (Path d : dirs) {
                Path pidFile = d.resolve(".runtime").resolve("app.pid");
                if (!Files.isRegularFile(pidFile))
                    continue;
                String content;
                try {
                    content = Files.readString(pidFile).trim();
                } catch (IOException e) {
                    continue;
                }
                long pid = parsePid(content);
                if (pid > 0 && isAlive(pid)
                        && (best == null || VersionUtil.compareVersion(versionOf(d), versionOf(best)) > 0)) {
                    best = d;
                }
            }
            return best == null ? null : versionOf(best);
        } catch (IOException e) {
            return null;
        }
    }

    private static String versionOf(Path dir) {
        return VersionUtil.versionOf(dir.getFileName().toString());
    }

    /**
     * 回写 registry.latest（幂等）：若目标版本条目存在且其 publishedAt 不是该 appId 内的最大值，
     * 则提升为 max+1（使其成为 registry.latest）；目标版本不在 registry → false。
     */
    static boolean ensureRegistryLatest(Path registryFile, String appId, String version) {
        try {
            String json = Files.readString(registryFile);
            Pattern entry = Pattern
                    .compile("\\{[^{}]*?\"appId\"\\s*:\\s*\"" + Pattern.quote(appId)
                            + "\"[^{}]*?\"version\"\\s*:\\s*\"" + Pattern.quote(version)
                            + "\"[^{}]*?\"publishedAt\"\\s*:\\s*(\\d+)[^{}]*?\\}");
            Matcher m = entry.matcher(json);
            if (!m.find()) {
                return false; // 目标版本不在 registry，无法回写
            }
            long cur = Long.parseLong(m.group(1));
            long maxAt = maxPublishedAt(json, appId);
            if (cur < maxAt) {
                String updated = json
                        .replace(m.group(0), m
                                .group(0)
                                .replaceFirst(
                                        Pattern.quote("\"publishedAt\"") + "\\s*:\\s*" + cur,
                                        "\"publishedAt\":" + (maxAt + 1)));
                Files.writeString(registryFile, updated);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 该 appId 在 registry 中的最大 publishedAt（无匹配返回 Long.MIN_VALUE）。 */
    private static long maxPublishedAt(String json, String appId) {
        Pattern p = Pattern
                .compile("\\{[^{}]*?\"appId\"\\s*:\\s*\"" + Pattern.quote(appId)
                        + "\"[^{}]*?\"publishedAt\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        long max = Long.MIN_VALUE;
        while (m.find()) {
            max = Math.max(max, Long.parseLong(m.group(1)));
        }
        return max;
    }

    /**
     * 回写 config.yml startup.version（运行声明权威）：
     * <ul>
     * <li>startup 段内已有 version 行 → 原位替换；</li>
     * <li>有 startup 段但无 version 行 → 段尾插入；</li>
     * <li>无 startup 段 → 文件末尾追加 startup 段；</li>
     * </ul>
     * config.yml 不存在 → false。
     */
    static boolean writeConfigStartupVersion(Path configFile, String version) {
        try {
            if (!Files.isRegularFile(configFile))
                return false;
            String yml = Files.readString(configFile);
            String[] lines = yml.split("\n", -1);
            int startupIdx = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].trim().equals("startup:")) {
                    startupIdx = i;
                    break;
                }
            }
            boolean replaced = false;
            if (startupIdx >= 0) {
                for (int i = startupIdx + 1; i < lines.length; i++) {
                    String ln = lines[i];
                    if (!ln.isBlank() && !Character.isWhitespace(ln.charAt(0))) {
                        break; // 离开 startup 段
                    }
                    if (ln.trim().startsWith("version:")) {
                        lines[i] = indentOf(ln) + "version: " + version;
                        replaced = true;
                        break;
                    }
                }
            }
            if (!replaced) {
                if (startupIdx >= 0) {
                    // 段尾（下一个顶格行）前插入 version 行
                    int insertAt = lines.length;
                    for (int i = startupIdx + 1; i < lines.length; i++) {
                        String ln = lines[i];
                        if (!ln.isBlank() && !Character.isWhitespace(ln.charAt(0))) {
                            insertAt = i;
                            break;
                        }
                    }
                    String[] nlines = new String[lines.length + 1];
                    System.arraycopy(lines, 0, nlines, 0, insertAt);
                    nlines[insertAt] = "  version: " + version;
                    System.arraycopy(lines, insertAt, nlines, insertAt + 1, lines.length - insertAt);
                    lines = nlines;
                } else {
                    // 文件末尾追加 startup 段
                    String sep = yml.isEmpty() || yml.endsWith("\n") ? "" : "\n";
                    Files.writeString(configFile, yml + sep + "startup:\n  version: " + version + "\n");
                    return true;
                }
            }
            Files.writeString(configFile, String.join("\n", lines));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String indentOf(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    /**
     * 按 --keep 清理旧版本目录：保留版本号最大的 keep 个 + 目标版本目录（防降级切换误删目标）；
     * keep &lt;= 0 不清理。
     */
    static void cleanupOldVersions(Path installedDir, String appId, String keepVersion, int keep) {
        if (keep <= 0 || !Files.isDirectory(installedDir))
            return;
        try (Stream<Path> list = Files.list(installedDir)) {
            List<Path> dirs = list
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(appId + "-"))
                    .sorted((a, b) -> VersionUtil.compareVersion(versionOf(b), versionOf(a)))
                    .toList();
            if (dirs.isEmpty())
                return;
            List<Path> keepDirs = new ArrayList<>(dirs.subList(0, Math.min(keep, dirs.size())));
            if (keepVersion != null) {
                Path targetDir = installedDir.resolve(appId + "-" + keepVersion);
                if (!keepDirs.contains(targetDir)) {
                    keepDirs.add(targetDir);
                }
            }
            for (Path d : dirs) {
                if (!keepDirs.contains(d)) {
                    System.out.println("[AppRunnerBoot] 清理旧版本目录: " + d.getFileName());
                    deleteTree(d);
                }
            }
        } catch (IOException e) {
            System.err.println("[AppRunnerBoot] 警告：清理旧版本失败: " + e.getMessage());
        }
    }

    /** 递归删除目录（含只读文件容错）。 */
    private static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 忽略：单个文件删除失败不阻塞其余清理
                }
            });
        } catch (IOException ignored) {
            // 忽略：目录已不存在等
        }
    }

    private static String optionValue(List<String> args, String name) {
        for (int i = 0; i < args.size() - 1; i++) {
            if (name.equals(args.get(i))) {
                return args.get(i + 1);
            }
        }
        return null;
    }

}
