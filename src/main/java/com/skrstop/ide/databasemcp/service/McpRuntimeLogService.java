package com.skrstop.ide.databasemcp.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.skrstop.ide.databasemcp.settings.McpSettingsState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    private final ReentrantLock lock = new ReentrantLock();

    public static McpRuntimeLogService getInstance() {
        return ApplicationManager.getApplication().getService(McpRuntimeLogService.class);
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
     * 获取最后 MAX_ENTRIES 条日志
     * 使用高效的反向读取，避免一次性加载整个大文件到内存
     */
    public List<String> list() {
        lock.lock();
        try {
            Path path = logFilePath();
            if (!Files.exists(path)) {
                return List.of();
            }
            return readLastNLines(path, getMaxEntries());
        } catch (IOException ex) {
            LOG.warn("Failed to read runtime log file", ex);
            return List.of();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 搜索日志
     * 只在最近的 MAX_ENTRIES 条日志中搜索，避免搜索过多数据
     */
    public List<String> search(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return list();
        }

        lock.lock();
        try {
            Path path = logFilePath();
            if (!Files.exists(path)) {
                return List.of();
            }

            // 只在最近的日志行中搜索
            List<String> recentLines = readLastNLines(path, getMaxEntries());
            List<String> matched = new ArrayList<>();
            for (String line : recentLines) {
                if (line.toLowerCase(Locale.ROOT).contains(q)) {
                    matched.add(line);
                }
            }
            return matched;
        } catch (IOException ex) {
            LOG.warn("Failed to search runtime log file", ex);
            return List.of();
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
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

    private void append(String level, String message) {
        String line = String.format(Locale.ROOT, "[%s] %s", level, message);

        if ("ERROR".equals(level)) {
            RUNTIME_LOGGER.error(line);
        } else if ("WARN".equals(level)) {
            RUNTIME_LOGGER.warn(line);
        } else {
            RUNTIME_LOGGER.info(line);
        }

        lock.lock();
        try {
            Path path = logFilePath();
            ensureParent(path);

            // 检查文件大小，如果超过限制则进行轮转
            if (Files.exists(path) && Files.size(path) >= getMaxFileSize()) {
                rotateLogFile(path);
            }

            Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
        } catch (IOException ex) {
            LOG.warn("Failed to append runtime log file", ex);
        } finally {
            lock.unlock();
        }
    }

    private Path logFilePath() {
        return Path.of(PathManager.getLogPath(), LOG_FILE_NAME);
    }

    private void ensureParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    /**
     * 高效读取文件末尾的最后 N 行日志
     * 使用反向缓冲读取，避免一次性加载整个文件到内存
     * 对于大文件（MB级别），仅加载末尾512KB数据
     */
    private List<String> readLastNLines(Path path, int maxLines) throws IOException {
        if (maxLines <= 0 || !Files.exists(path)) {
            return List.of();
        }

        long fileSize = Files.size(path);
        if (fileSize == 0) {
            return List.of();
        }

        // Read with UTF-8 line decoder to avoid breaking multibyte characters (e.g. Chinese).
        ArrayDeque<String> tail = new ArrayDeque<>(Math.min(maxLines, 4096));
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8),
                getReadBufferSize()
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == maxLines) {
                    tail.removeFirst();
                }
                tail.addLast(line);
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
}
