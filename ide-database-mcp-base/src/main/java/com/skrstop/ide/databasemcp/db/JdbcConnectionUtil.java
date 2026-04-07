package com.skrstop.ide.databasemcp.db;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.skrstop.ide.databasemcp.service.McpRuntimeLogService;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Iterator;
import java.util.Locale;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.stream.Stream;

final class JdbcConnectionUtil {
    JdbcConnectionUtil() {
    }

    private static McpRuntimeLogService logService() {
        return ApplicationManager.getApplication() == null
                ? null
                : ApplicationManager.getApplication().getService(McpRuntimeLogService.class);
    }

    private static void logInfo(String message) {
        McpRuntimeLogService service = logService();
        if (service != null) {
            service.info("jdbc", message);
        }
    }

    private static void logWarn(String message) {
        McpRuntimeLogService service = logService();
        if (service != null) {
            service.warn("jdbc", message);
        }
    }

    Connection openConnection(Object dataSource, String dataSourceName) throws Exception {
        String url = DbReflectionUtil.invokeString(dataSource, "getUrl");
        String user = DbReflectionUtil.invokeString(dataSource, "getUser");
        if (user == null || user.isBlank()) {
            user = DbReflectionUtil.invokeString(dataSource, "getUsername");
        }
        String password = loadPasswordViaIdeaCredentials(dataSource);
        String driverClass = DbReflectionUtil.invokeString(dataSource, "getDriverClass");

        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Data source URL is empty: " + dataSourceName);
        }

        if (driverClass != null && !driverClass.isBlank()) {
            try {
                Class.forName(driverClass);
            } catch (ClassNotFoundException ex) {
                logWarn("JDBC driver class not found: " + driverClass + ", " + ex.getMessage());
            }
        }

        Properties props = buildConnectionProperties(dataSource, user, password);
        try {
            return DriverManager.getConnection(url, props);
        } catch (SQLException ex) {
            if (!isNoSuitableDriverError(ex) || driverClass == null || driverClass.isBlank()) {
                throw ex;
            }
            if (!registerDriverFromIdeaJdbcDrivers(driverClass)) {
                throw ex;
            }
            return DriverManager.getConnection(url, props);
        }
    }

    private Properties buildConnectionProperties(Object dataSource, String user, String password) {
        Properties props = new Properties();
        try {
            Method method = dataSource.getClass().getMethod("getConnectionProperties");
            Object value = method.invoke(dataSource);
            if (value instanceof Properties fromDataSource) {
                props.putAll(fromDataSource);
            }
        } catch (Exception ignored) {
            // Some datasource implementations do not expose connection properties.
        }

        if (user != null && !user.isBlank()) {
            props.setProperty("user", user);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        return props;
    }

    private String loadPasswordViaIdeaCredentials(Object dataSource) {
        try {
            Class<?> connectionPointClass = Class.forName("com.intellij.database.dataSource.DatabaseConnectionPoint");
            if (!connectionPointClass.isInstance(dataSource)) {
                return null;
            }

            Class<?> credentialsClass = Class.forName("com.intellij.database.access.DatabaseCredentials");
            Method getInstance = credentialsClass.getMethod("getInstance");
            Object credentials = getInstance.invoke(null);
            if (credentials == null) {
                return null;
            }

            Method loadPassword = credentialsClass.getMethod("loadPassword", connectionPointClass);
            Object oneTimeString = loadPassword.invoke(credentials, dataSource);
            if (oneTimeString == null) {
                return null;
            }

            try {
                Method toString = oneTimeString.getClass().getMethod("toString", boolean.class);
                Object value = toString.invoke(oneTimeString, false);
                return value == null ? null : String.valueOf(value);
            } catch (NoSuchMethodException ignored) {
                return String.valueOf(oneTimeString);
            }
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (Exception ex) {
            logInfo("Failed to load password via IDE credentials: " + ex.getMessage());
            return null;
        }
    }

    private boolean isNoSuitableDriverError(SQLException ex) {
        String message = ex.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("no suitable driver");
    }

    private boolean registerDriverFromIdeaJdbcDrivers(String driverClass) {
        Path driverRoot = Path.of(PathManager.getConfigPath(), "jdbc-drivers");
        if (!Files.isDirectory(driverRoot)) {
            return false;
        }

        String classEntry = driverClass.replace('.', '/') + ".class";
        try (Stream<Path> paths = Files.walk(driverRoot)) {
            Iterator<Path> it = paths
                    .filter(p -> p.toString().endsWith(".jar"))
                    .iterator();
            while (it.hasNext()) {
                Path jar = it.next();
                if (!jarContainsClass(jar, classEntry)) {
                    continue;
                }

                URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
                Class<?> loaded = Class.forName(driverClass, true, loader);
                Object instance = loaded.getDeclaredConstructor().newInstance();
                if (instance instanceof Driver driver) {
                    DriverManager.registerDriver(new DriverShim(driver));
                    logInfo("Registered JDBC driver " + driverClass + " from " + jar);
                    return true;
                }
            }
        } catch (Exception e) {
            logWarn("Failed to register JDBC driver from IDE jdbc-drivers: " + driverClass + ", " + e.getMessage());
        }
        return false;
    }

    private boolean jarContainsClass(Path jarPath, String classEntry) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return jar.getEntry(classEntry) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final class DriverShim implements Driver {
        private final Driver delegate;

        private DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}

