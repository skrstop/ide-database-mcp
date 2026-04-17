package com.skrstop.ide.databasemcp.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.skrstop.ide.databasemcp.settings.McpSettingsState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

@Service(Service.Level.APP)
public final class McpRuntimeLogService {
    private static final Logger LOG = Logger.getInstance(McpRuntimeLogService.class);
    private static final Logger RUNTIME_LOGGER = Logger.getInstance("DatabaseMcpRuntime");
    private static final String LOG_FILE_NAME = "ide_database_mcp_runtime.log";
    private static final DateTimeFormatter DATATIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    private final ReentrantLock lock = new ReentrantLock();

    // 内存环形缓冲区，避免每秒从磁盘读取整个日志文件
    private final ArrayDeque<String> memoryBuffer = new ArrayDeque<>();

    // 内容版本号，每次 append / clear 递增。UI 层可据此快速判断是否需要刷新，
    // 避免无新日志时仍然全量拷贝 memoryBuffer + String.join。
    private final java.util.concurrent.atomic.AtomicLong version = new java.util.concurrent.atomic.AtomicLong();

    // 追踪当前日志文件大小，避免每次 append 都调用 Files.size()
    private long currentFileSize = -1;

    // 缓存日志文件路径，避免每次 append 都重复创建 Path 对象
    private volatile Path cachedLogFilePath;

    // 标记父目录已确认存在，避免每次 append 都调用 Files.exists()
    private volatile boolean parentDirectoryEnsured;

    public static McpRuntimeLogService getInstance() {
        return ApplicationManager.getApplication().getService(McpRuntimeLogService.class);
    }

    /**
     * null-safe 安全获取实例（ApplicationManager 未初始化时返回 null）。
     */
    private static McpRuntimeLogService safeGetInstance() {
        com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
        return app == null ? null : app.getService(McpRuntimeLogService.class);
    }

    /**
     * 静态 info 日志入口，null-safe，可在任意类直接调用无需持有实例。
     *
     * @param source  日志来源标签（如 "jdbc"、"discovery" 等）
     * @param message 日志内容
     */
    public static void logInfo(String source, String message) {
        McpRuntimeLogService svc = safeGetInstance();
        if (svc != null) {
            svc.info(source, message);
        }
    }

    /**
     * 静态 warn 日志入口，null-safe。
     *
     * @param source  日志来源标签
     * @param message 日志内容
     */
    public static void logWarn(String source, String message) {
        McpRuntimeLogService svc = safeGetInstance();
        if (svc != null) {
            svc.warn(source, message);
        }
    }

    /**
     * 静态 error 日志入口，null-safe。
     *
     * @param source  日志来源标签
     * @param message 日志内容
     */
    public static void logError(String source, String message) {
        McpRuntimeLogService svc = safeGetInstance();
        if (svc != null) {
            svc.error(source, message);
        }
    }

    // Helper methods to get config values from settings
    private int getMaxEntries() {
        return McpSettingsState.getInstance().getMaxEntries();
    }

    private long getMaxFileSize() {
        return McpSettingsState.getInstance().getMaxFileSize();
    }

    private int getMaxLogFiles() {
        return McpSettingsState.getInstance().getMaxLogFiles();
    }

    private int getReadBufferSize() {
        return McpSettingsState.getInstance().getReadBufferSize();
    }

    public void info(String source, String message) {
        append("INFO", message);
    }

    public void warn(String source, String message) {
        append("WARN", message);
    }

    public void error(String source, String message) {
        append("ERROR", message);
    }

    /**
     * 获取最后 MAX_ENTRIES 条日志。
     * 从内存环形缓冲区直接返回，避免每秒从磁盘读取整个日志文件。
     */
    public List<String> list() {
        lock.lock();
        try {
            if (memoryBuffer.isEmpty()) {
                // 首次调用或 clear 后，从磁盘加载一次填充缓冲区
                loadMemoryBufferFromDisk();
            }
            return new ArrayList<>(memoryBuffer);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 增量日志快照，供 UI 层增量渲染使用。
     *
     * @param appended     相对 {@code sinceVersion} 新增的行（按时间顺序）
     * @param keptOldLines UI 应当保留的、已渲染的"老行"数量；差值即需要从 document 开头裁剪的行数
     * @param version      本次返回的当前版本号，UI 下次传入以获取新增
     * @param fullReset    true 表示 UI 应当丢弃已渲染内容并用 {@code appended} 重建（首次调用 / clear 后 / 超出环形缓冲窗口）
     */
    public record LogSnapshot(List<String> appended, int keptOldLines, long version, boolean fullReset) {
    }

    /**
     * 获取自 {@code sinceVersion} 之后的日志增量。
     * 调用者应按返回的 {@link LogSnapshot#fullReset} 决定是全量重建还是在已渲染内容之上做增量追加。
     *
     * <p>当 {@code sinceVersion <= 0} 或请求区间已超出环形缓冲窗口时，返回 {@code fullReset=true} + 完整快照；
     * 否则返回 {@code (appended, keptOldLines)} 供 UI 增量更新。
     *
     * @param sinceVersion UI 上次渲染对应的版本号；首次传 0 或负数
     */
    public LogSnapshot listSince(long sinceVersion) {
        lock.lock();
        try {
            if (memoryBuffer.isEmpty()) {
                loadMemoryBufferFromDisk();
            }
            long current = version.get();
            int bufSize = memoryBuffer.size();
            // 缓冲区中第一行对应的 version 值：current - bufSize + 1
            // （append 时先 addLast 再 incrementAndGet，因此最后一行的 version 等于 current）
            long firstBuffered = current - bufSize + 1;

            // 全量重建：首次 / 已超出窗口
            if (sinceVersion <= 0 || sinceVersion < firstBuffered - 1) {
                return new LogSnapshot(new ArrayList<>(memoryBuffer), 0, current, true);
            }
            // 无新增
            if (sinceVersion >= current) {
                return new LogSnapshot(List.of(), bufSize, current, false);
            }

            // 增量：sinceVersion 之前的行数 = sinceVersion - firstBuffered + 1
            int renderedInBuffer = (int) (sinceVersion - firstBuffered + 1);
            int appendedCount = bufSize - renderedInBuffer;
            List<String> appended = new ArrayList<>(appendedCount);
            int idx = 0;
            for (String l : memoryBuffer) {
                if (idx++ >= renderedInBuffer) {
                    appended.add(l);
                }
            }
            return new LogSnapshot(appended, renderedInBuffer, current, false);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 搜索日志。
     * 从内存缓冲区搜索，避免磁盘 IO；使用 regionMatches 做大小写无关匹配，避免每行 toLowerCase 产生临时字符串。
     */
    public List<String> search(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return list();
        }

        lock.lock();
        try {
            if (memoryBuffer.isEmpty()) {
                loadMemoryBufferFromDisk();
            }
            List<String> matched = new ArrayList<>();
            for (String line : memoryBuffer) {
                if (containsIgnoreCase(line, q)) {
                    matched.add(line);
                }
            }
            return matched;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 大小写无关子串匹配，基于 {@link String#regionMatches(boolean, int, String, int, int)} 零分配实现。
     */
    private static boolean containsIgnoreCase(String haystack, String needle) {
        int hLen = haystack.length();
        int nLen = needle.length();
        if (nLen == 0) {
            return true;
        }
        if (hLen < nLen) {
            return false;
        }
        int max = hLen - nLen;
        for (int i = 0; i <= max; i++) {
            if (haystack.regionMatches(true, i, needle, 0, nLen)) {
                return true;
            }
        }
        return false;
    }

    public void clear() {
        lock.lock();
        try {
            memoryBuffer.clear();
            currentFileSize = 0;
            version.incrementAndGet();
            Path path = logFilePath();
            ensureParent(path);
            Files.writeString(path, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException ex) {
            LOG.warn("Failed to clear runtime log file", ex);
        } finally {
            lock.unlock();
        }
    }

    public String getLogFilePath() {
        return logFilePath().toAbsolutePath().toString();
    }

    /**
     * 返回当前日志内容版本号。每次 append / clear 都会递增。
     * UI 轮询时可先比对版本号，未变化时直接跳过后续的拷贝与拼接。
     */
    public long version() {
        return version.get();
    }

    private void append(String level, String message) {
        String line = String.format(Locale.ROOT, "%s [%s] %s", LocalDateTime.now().format(DATATIME_FORMAT), level, message);

        if ("ERROR".equals(level)) {
            RUNTIME_LOGGER.error(line);
        } else if ("WARN".equals(level)) {
            RUNTIME_LOGGER.warn(line);
        } else {
            RUNTIME_LOGGER.info(line);
        }

        lock.lock();
        try {
            // 同步写入内存环形缓冲区
            int maxEntries = getMaxEntries();
            if (memoryBuffer.size() >= maxEntries) {
                memoryBuffer.removeFirst();
            }
            memoryBuffer.addLast(line);
            version.incrementAndGet();

            // 写入磁盘文件
            Path path = logFilePath();
            ensureParent(path);

            // 延迟初始化文件大小追踪
            if (currentFileSize < 0) {
                currentFileSize = Files.exists(path) ? Files.size(path) : 0;
            }

            // 使用追踪的文件大小判断是否需要轮转，避免每次调用 Files.size()
            if (currentFileSize >= getMaxFileSize()) {
                rotateLogFile(path);
                currentFileSize = 0;
            }

            byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
            currentFileSize += bytes.length;
        } catch (IOException ex) {
            LOG.warn("Failed to append runtime log file", ex);
        } finally {
            lock.unlock();
        }
    }

    private Path logFilePath() {
        Path path = cachedLogFilePath;
        if (path == null) {
            path = Path.of(PathManager.getLogPath(), LOG_FILE_NAME);
            cachedLogFilePath = path;
        }
        return path;
    }

    private void ensureParent(Path path) throws IOException {
        if (parentDirectoryEnsured) {
            return;
        }
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        parentDirectoryEnsured = true;
    }

    /**
     * 高效读取文件末尾的最后 N 行日志。
     *
     * <p>实现策略：
     * <ul>
     *   <li>小文件（≤ {@code tailWindowBytes}）：直接顺序读取。</li>
     *   <li>大文件：用 {@link FileChannel} 直接 seek 到文件尾部窗口起点，仅读取尾部数据，
     *       避免整文件扫描；由于 seek 位置可能落在多字节 UTF-8 字符中间，统一丢弃窗口中
     *       的第一行（可能是半截），其后的行均为完整行。</li>
     * </ul>
     *
     * <p>首次打开 ToolWindow 或 clear 后首次调用 {@code list()} 时会走此路径，
     * 优化后 IO 量从"整文件大小"降至"几百 KB"量级。
     */
    private List<String> readLastNLines(Path path, int maxLines) throws IOException {
        if (maxLines <= 0 || !Files.exists(path)) {
            return List.of();
        }

        long fileSize = Files.size(path);
        if (fileSize == 0) {
            return List.of();
        }

        int bufferSize = getReadBufferSize();
        // 尾部窗口：至少 512KB，或按每行约 1KB 估算以覆盖 maxLines 行；取较大者并与文件大小比较
        long tailWindowBytes = Math.max(512L * 1024L, (long) maxLines * 1024L);
        long startOffset = fileSize > tailWindowBytes ? fileSize - tailWindowBytes : 0L;
        boolean skipFirstPartialLine = startOffset > 0;

        ArrayDeque<String> tail = new ArrayDeque<>(Math.min(maxLines, 4096));
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(startOffset);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8),
                    bufferSize
            )) {
                if (skipFirstPartialLine) {
                    // 首行可能落在多字节字符中间或是不完整的上一行，直接丢弃确保后续行边界正确
                    reader.readLine();
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if (tail.size() == maxLines) {
                        tail.removeFirst();
                    }
                    tail.addLast(line);
                }
            }
        }
        return new ArrayList<>(tail);
    }

    /**
     * 日志文件轮转：当文件超过大小限制时，重命名当前文件并创建新文件
     * 只在需要时触发，避免性能影响
     */
    private void rotateLogFile(Path path) {
        try {
            Path parent = path.getParent();
            if (parent == null) {
                return;
            }

            // 生成备份文件名，使用时间戳
            String timestamp = String.valueOf(System.currentTimeMillis());
            String baseName = LOG_FILE_NAME.substring(0, LOG_FILE_NAME.lastIndexOf('.'));
            String extension = LOG_FILE_NAME.substring(LOG_FILE_NAME.lastIndexOf('.'));
            Path backupPath = parent.resolve(baseName + "." + timestamp + extension);

            // 重命名当前日志文件为备份
            Files.move(path, backupPath);

            // 异步清理旧的日志文件，避免阻塞当前线程
            cleanOldLogFilesAsync(parent, baseName, extension);
        } catch (IOException ex) {
            LOG.warn("Failed to rotate log file", ex);
        }
    }

    /**
     * 异步清理旧的日志文件，只保留最近的 MAX_LOG_FILES 个文件
     * 使用 ApplicationManager 的线程池执行，避免显式线程创建导致的不确定性
     */
    private void cleanOldLogFilesAsync(Path parent, String baseName, String extension) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                List<Path> logFiles = new ArrayList<>();
                try (var stream = Files.list(parent)) {
                    stream.filter(p -> {
                        String fileName = p.getFileName().toString();
                        return fileName.startsWith(baseName) && fileName.endsWith(extension);
                    }).sorted((a, b) -> {
                        // 按修改时间降序排序
                        try {
                            return Long.compare(Files.getLastModifiedTime(b).toMillis(),
                                    Files.getLastModifiedTime(a).toMillis());
                        } catch (IOException e) {
                            return 0;
                        }
                    }).forEach(logFiles::add);
                }

                // 删除超出限制的旧文件
                if (logFiles.size() > getMaxLogFiles()) {
                    for (int i = getMaxLogFiles(); i < logFiles.size(); i++) {
                        try {
                            Files.delete(logFiles.get(i));
                        } catch (IOException ex) {
                            LOG.warn("Failed to delete old log file: " + logFiles.get(i), ex);
                        }
                    }
                }
            } catch (IOException ex) {
                LOG.warn("Failed to clean old log files", ex);
            }
        });
    }

    /**
     * 从磁盘日志文件加载最后 N 行填充内存缓冲区。
     * 仅在首次调用 list()/search() 或 clear() 后执行一次。
     * 调用方必须持有 lock。
     */
    private void loadMemoryBufferFromDisk() {
        try {
            Path path = logFilePath();
            if (!Files.exists(path)) {
                return;
            }
            List<String> lines = readLastNLines(path, getMaxEntries());
            memoryBuffer.clear();
            memoryBuffer.addAll(lines);
            currentFileSize = Files.size(path);
        } catch (IOException ex) {
            LOG.warn("Failed to load memory buffer from disk", ex);
        }
    }
}
