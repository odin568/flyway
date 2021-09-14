/*
 * Copyright (C) Red Gate Software Ltd 2010-2021
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flywaydb.gradle.task;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.internal.configuration.ConfigUtils;
import org.flywaydb.core.internal.jdbc.DriverDataSource;
import org.flywaydb.core.internal.util.StringUtils;
import org.flywaydb.gradle.FlywayExtension;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetOutput;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

import static org.flywaydb.core.internal.configuration.ConfigUtils.putIfSet;

/**
 * A base class for all Flyway tasks.
 */
public abstract class AbstractFlywayTask extends DefaultTask {
    /**
     * The default Gradle configurations to use.
     */
    // #2272: Gradle 4.x introduced additional configuration names and Gradle 5.0 deprecated some old ones.
    // -> Rely on historic ones for Gradle 3.x
    private static final String[] DEFAULT_CONFIGURATIONS_GRADLE3 = {"compileClasspath", "runtime", "testCompileClasspath", "testRuntime"};
    // -> And use new ones with Gradle 4.x and newer
    private static final String[] DEFAULT_CONFIGURATIONS_GRADLE45 = {"compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath"};

    /**
     * The flyway {} block in the build script.
     */
    protected FlywayExtension extension;

    /**
     * The fully qualified classname of the JDBC driver to use to connect to the database.
     */
    public String driver;

    /**
     * The JDBC url to use to connect to the database.
     */
    public String url;

    /**
     * The user to use to connect to the database.
     */
    public String user;

    /**
     * The password to use to connect to the database.
     */
    public String password;

    /**
     * The maximum number of retries when attempting to connect to the database. After each failed attempt, Flyway will
     * wait 1 second before attempting to connect again, up to the maximum number of times specified by connectRetries.
     * The interval between retries doubles with each subsequent attempt.
     * (default: 0)
     * <p>Also configurable with Gradle or System Property: ${flyway.connectRetries}</p>
     */
    public int connectRetries;

    /**
     * The maximum time between retries when attempting to connect to the database in seconds. This will cap the interval
     * between connect retry to the value provided.
     * (default: 120)
     * <p>Also configurable with Gradle or System Property: ${flyway.connectRetriesInterval}</p>
     */
    public int connectRetriesInterval;

    /**
     * The SQL statements to run to initialize a new database connection immediately after opening it.
     * (default: {@code null})
     * <p>Also configurable with Gradle or System Property: ${flyway.initSql}</p>
     */
    public String initSql;

    /**
     * The name of the schema history table that will be used by Flyway. (default: flyway_schema_history)
     * By default, (single-schema mode) the schema history table is placed in the default schema for the connection provided by the datasource.
     * When the {@code flyway.schemas} property is set (multi-schema mode), the schema history table is placed in the first schema of the list,
     * or in the schema specified to {@code flyway.defaultSchema}.
     * <p>Also configurable with Gradle or System Property: ${flyway.table}</p>
     */
    public String table;

    /**
     * The tablespace where to create the schema history table that will be used by Flyway.
     * If not specified, Flyway uses the default tablespace for the database connection.
     * This setting is only relevant for databases that do support the notion of tablespaces. Its value is simply
     * ignored for all others.
     * <p>Also configurable with Gradle or System Property: ${flyway.tablespace}</p>
     */
    public String tablespace;

    /**
     * The default schema managed by Flyway. This schema name is case-sensitive. If not specified, but <i>schemas</i>
     * is, Flyway uses the first schema in that list. If that is also not specified, Flyway uses the default schema for the
     * database connection.
     * <p>Consequences:</p>
     * <ul>
     * <li>This schema will be the one containing the schema history table.</li>
     * <li>This schema will be the default for the database connection (provided the database supports this concept).</li>
     * </ul>
     * <p>Also configurable with Gradle or System Property: ${flyway.defaultSchema}</p>
     */
    public String defaultSchema;

    /**
     * Whether Flyway should attempt to create the schemas specified in the <i>schemas</i> property.
     */
    public Boolean createSchemas;

    /**
     * The schemas managed by Flyway. These schema names are case-sensitive. If not specified, Flyway uses
     * the default schema for the database connection. If <i>defaultSchema</i> is not specified, then the first of
     * this list also acts as default schema.
     * <p>Consequences:</p>
     * <ul>
     * <li>Flyway will automatically attempt to create all these schemas, unless they already exist.</li>
     * <li>The schemas will be cleaned in the order of this list.</li>
     * <li>If Flyway created them, the schemas themselves will be dropped when cleaning.</li>
     * </ul>
     * <p>Also configurable with Gradle or System Property: ${flyway.schemas} (comma-separated list)</p>
     */
    public String[] schemas;

    /**
     * The version to tag an existing schema with when executing baseline. (default: 1)
     */
    public String baselineVersion;

    /**
     * The description to tag an existing schema with when executing baseline. (default: &lt;&lt; Flyway Baseline &gt;&gt;)
     */
    public String baselineDescription;

    /**
     * Locations to scan recursively for migrations.
     * The location type is determined by its prefix.
     * Unprefixed locations or locations starting with {@code classpath:} point to a package on the classpath and may
     * contain both SQL and Java-based migrations.
     * Locations starting with {@code filesystem:} point to a directory on the filesystem, may only
     * contain SQL migrations and are only scanned recursively down non-hidden directories.
     * (default: filesystem:src/main/resources/db/migration)
     */
    public String[] locations;

    /**
     * The fully qualified class names of the custom MigrationResolvers to be used in addition (default)
     * or as a replacement (using skipDefaultResolvers) to the built-in ones for resolving Migrations to
     * apply. (default: none)
     */
    public String[] resolvers;

    /**
     * If set to true, default built-in resolvers will be skipped, only custom migration resolvers will be used.
     * (default: false)
     */
    public Boolean skipDefaultResolvers;

    /**
     * The file name prefix for versioned SQL migrations. (default: V)
     * Versioned SQL migrations have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix,
     * which using the defaults translates to V1_1__My_description.sql
     * <p>Also configurable with Gradle or System Property: ${flyway.sqlMigrationPrefix}</p>
     */
    public String sqlMigrationPrefix;

    /**
     * The file name prefix for state scripts. (default: S)
     * They have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix,
     * which using the defaults translates to S1.1__My_description.sql
     * <i>Flyway Teams only</i>
     * <p>Also configurable with Gradle or System Property: ${flyway.stateScriptPrefix}</p>
     */
    public String stateScriptPrefix;

    /**
     * The file name prefix for undo SQL migrations. (default: U)
     * Undo SQL migrations are responsible for undoing the effects of the versioned migration with the same version.
     * They have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix,
     * which using the defaults translates to U1.1__My_description.sql
     * <i>Flyway Teams only</i>
     * <p>Also configurable with Gradle or System Property: ${flyway.undoSqlMigrationPrefix}</p>
     */
    public String undoSqlMigrationPrefix;

    /**
     * The file name prefix for repeatable SQL migrations (default: R).
     * Repeatable SQL migrations have the following file name structure: prefixSeparatorDESCRIPTIONsuffix,
     * which using the defaults translates to R__My_description.sql
     * <p>Also configurable with Gradle or System Property: ${flyway.repeatableSqlMigrationPrefix}</p>
     */
    public String repeatableSqlMigrationPrefix;

    /**
     * The file name prefix for SQL migrations
     * SQL migrations have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix,
     * which using the defaults translates to V1_1__My_description.sql
     */
    public String sqlMigrationSeparator;

    /**
     * The file name suffixes for SQL migrations. (default: .sql)
     * SQL migrations have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix,
     * which using the defaults translates to V1_1__My_description.sql
     * Multiple suffixes (like .sql,.pkg,.pkb) can be specified for easier compatibility with other tools such as
     * editors with specific file associations.
     * <p>Also configurable with Gradle or System Property: ${flyway.sqlMigrationSuffixes}</p>
     */
    public String[] sqlMigrationSuffixes;

    /**
     * The encoding of SQL migrations.
     */
    public String encoding;

    /**
     * Whether Flyway should try to automatically detect SQL migration file encoding
     * <i>Flyway Teams only</i>
     * <p>Also configurable with Gradle or System Property: ${flyway.detectEncoding}</p>
     */
    public Boolean detectEncoding;

    /**
     * Placeholders to replace in SQL migrations.
     */
    public Map<Object, Object> placeholders;
    
    /**
     * Properties to pass to the JDBC driver object.
     *
     * <i>Flyway Teams only</i>
     * <p>Also configurable with Gradle or System Property: ${flyway.jdbcProperties}</p>
     */
    public Map<Object, Object> jdbcProperties;

    /**
     * Whether placeholders should be replaced.
     */
    public Boolean placeholderReplacement;

    /**
     * The prefix of every placeholder.
     */
    public String placeholderPrefix;

    /**
     * The suffix of every placeholder.
     */
    public String placeholderSuffix;

    /**
     * The prefix of every script placeholder.
     */
    public String scriptPlaceholderPrefix;

    /**
     * The suffix of every script placeholder.
     */
    public String scriptPlaceholderSuffix;

    /**
     * The target version up to which Flyway should consider migrations.
     * Migrations with a higher version number will be ignored.
     * Special values:
     * <ul>
     * <li>{@code current}: Designates the current version of the schema</li>
     * <li>{@code latest}: The latest version of the schema, as defined by the migration with the highest version</li>
     * <li>
     *     &lt;version&gt;? (end with a '?'): Instructs Flyway not to fail if the target version doesn't exist.
     *     In this case, Flyway will go up to but not beyond the specified target
     *     (default: fail if the target version doesn't exist) <i>Flyway Teams only</i>
     * </li>
     * </ul>
     * Defaults to {@code latest}.
     */
    public String target;

    /**
     * Gets the migrations that Flyway should consider when migrating or undoing. Leave empty to consider all available migrations.
     * Migrations not in this list will be ignored.
     * Values should be the version for versioned migrations (e.g. 1, 2.4, 6.5.3) or the description for repeatable migrations (e.g. Insert_Data, Create_Table)
     * <i>Flyway Teams only</i>
     */
    public String[] cherryPick;

    /**
     * The loggers Flyway should use. Valid options are:
     *
     * <ul>
     *     <li>auto: Auto detect the logger (default behavior)</li>
     *     <li>console: Use stdout/stderr (only available when using the CLI)</li>
     *     <li>slf4j2: Use the slf4j2 logger</li>
     *     <li>log4j2: Use the log4j2 logger</li>
     *     <li>apache-commons: Use the Apache Commons logger</li>
     * </ul>
     *
     * Alternatively you can provide the fully qualified class name for any other logger to use that.
     */
    public String[] loggers;

    /**
     * An array of fully qualified FlywayCallback class implementations, or packages to scan for FlywayCallback implementations.
     */
    public String[] callbacks;

    /**
     * If set to true, default built-in callbacks will be skipped, only custom migration callbacks will be used.
     * <p>(default: false)</p>
     */
    public Boolean skipDefaultCallbacks;

    /**
     * Allows migrations to be run "out of order".
     */
    public Boolean outOfOrder;

    /**
     * Whether Flyway should skip actually executing the contents of the migrations and only update the schema history table.
     * This should be used when you have applied a migration manually (via executing the sql yourself, or via an ide), and
     * just want the schema history table to reflect this.
     * Use in conjunction with {@code cherryPick} to skip specific migrations instead of all pending ones.
     * <i>Flyway Teams only</i>
     */
    public Boolean skipExecutingMigrations;

    /**
     * Whether Flyway should output a table with the results of queries when executing migrations (default: true).
     * <i>Flyway Teams only</i>
     * <p>Also configurable with Gradle or System Property: ${flyway.outputQueryResults}</p>
     */
    public Boolean outputQueryResults;

    /**
     * Whether to automatically call validate or not when running migrate. (default: true)
     */
    public Boolean validateOnMigrate;

    /**
     * Whether to automatically call clean or not when a validation error occurs. (default: {@code false})
     * This is exclusively intended as a convenience for development. even though we strongly recommend not to change
     * migration scripts once they have been checked into SCM and run, this provides a way of dealing with this case in
     * a smooth manner. The database will be wiped clean automatically, ensuring that the next migration will bring you
     * back to the state checked into SCM.
     * <b>Warning! Do not enable in production!</b>
     * <p>Also configurable with Gradle or System Property: ${flyway.cleanOnValidationError}</p>
     */
    public Boolean cleanOnValidationError;

    /**
     * @deprecated Will remove in Flyway V9. Use {@code ignoreMigrationPatterns} instead.
     *
     * Ignore missing migrations when reading the schema history table. These are migrations that were performed by an
     * older deployment of the application that are no longer available in this version. For example: we have migrations
     * available on the classpath with versions 1.0 and 3.0. The schema history table indicates that a migration with version 2.0
     * (unknown to us) has also been applied. Instead of bombing out (fail fast) with an exception, a
     * warning is logged and Flyway continues normally. This is useful for situations where one must be able to deploy
     * a newer version of the application even though it doesn't contain migrations included with an older one anymore.
     * Note that if the most recently applied migration is removed, Flyway has no way to know it is missing and will
     * mark it as future instead.(default: {@code false})
     * <p>Also configurable with Gradle or System Property: ${flyway.ignoreMissingMigrations}</p>
     */
    public Boolean ignoreMissingMigrations;

    /**
     * @deprecated Will remove in Flyway V9. Use {@code ignoreMigrationPatterns} instead.
     *
     * Ignore ignored migrations when reading the schema history table. These are migrations that were added in between
     * already migrated migrations in this version. For example: we have migrations available on the classpath with
     * versions from 1.0 to 3.0. The schema history table indicates that version 1 was finished on 1.0.15, and the next
     * one was 2.0.0. But with the next release a new migration was added to version 1: 1.0.16. Such scenario is ignored
     * by migrate command, but by default is rejected by validate. When ignoreIgnoredMigrations is enabled, such case
     * will not be reported by validate command. This is useful for situations where one must be able to deliver
     * complete set of migrations in a delivery package for multiple versions of the product, and allows for further
     * development of older versions.(default: {@code false})
     * <p>Also configurable with Gradle or System Property: ${flyway.ignoreIgnoredMigrations}</p>
     */
    public Boolean ignoreIgnoredMigrations;

    /**
     * @deprecated Will remove in Flyway V9. Use {@code ignoreMigrationPatterns} instead.
     *
     * Ignore pending migrations when reading the schema history table. These are migrations that are available
     * but have not yet been applied. This can be useful for verifying that in-development migration changes
     * don't contain any validation-breaking changes of migrations that have already been applied to a production
     * environment, e.g. as part of a CI/CD process, without failing because of the existence of new migration versions.
     * (default: {@code false})
     * <p>Also configurable with Gradle or System Property: ${flyway.ignorePendingMigrations}</p>
     */
    public Boolean ignorePendingMigrations;

    /**
     * @deprecated Will remove in Flyway V9. Use {@code ignoreMigrationPatterns} instead.
     *
     * Ignore future migrations when reading the schema history table. These are migrations that were performed by a
     * newer deployment of the application that are not yet available in this version. For example: we have migrations
     * available on the classpath up to version 3.0. The schema history table indicates that a migration to version 4.0
     * (unknown to us) has already been applied. Instead of bombing out (fail fast) with an exception, a
     * warning is logged and Flyway continues normally. This is useful for situations where one must be able to redeploy
     * an older version of the application after the database has been migrated by a newer one. (default: {@code true})
     * <p>Also configurable with Gradle or System Property: ${flyway.ignoreFutureMigrations}</p>
     */
    public Boolean ignoreFutureMigrations;

    /**
     * Ignore migrations that match this comma-separated list of patterns when validating migrations.
     * Each pattern is of the form <migration_type>:<migration_state>
     * See https://flywaydb.org/documentation/configuration/parameters/ignoreMigrationPatterns for full details
     * Example: repeatable:missing,versioned:pending,*:failed
     * <i>Flyway Teams only</i>
     */
    public String[] ignoreMigrationPatterns;

    /**
     * Whether to validate migrations and callbacks whose scripts do not obey the correct naming convention. A failure can be
     * useful to check that errors such as case sensitivity in migration prefixes have been corrected.
     *{@code false} to continue normally, {@code true} to fail fast with an exception. (default: {@code false})
     */
    public Boolean validateMigrationNaming;

    /**
     * Whether to disable clean. (default: {@code false})
     * This is especially useful for production environments where running clean can be quite a career limiting move.
     */
    public Boolean cleanDisabled;

    /**
     * Whether to automatically call baseline when migrate is executed against a non-empty schema with no schema history table.
     * This schema will then be baselined with the {@code baselineVersion} before executing the migrations.
     * Only migrations above {@code baselineVersion} will then be applied.
     * This is useful for initial Flyway production deployments on projects with an existing DB.
     * Be careful when enabling this as it removes the safety net that ensures
     * Flyway does not migrate the wrong database in case of a configuration mistake!
     * {@code true} if baseline should be called on migrate for non-empty schemas, {@code false} if not. (default: {@code false})
     */
    public Boolean baselineOnMigrate;

    /**
     * Whether to allow mixing transactional and non-transactional statements within the same migration. Enabling this
     * automatically causes the entire affected migration to be run without a transaction.
     *
     * Note that this is only applicable for PostgreSQL, Aurora PostgreSQL, SQL Server and SQLite which all have
     * statements that do not run at all within a transaction.
     * This is not to be confused with implicit transaction, as they occur in MySQL or Oracle, where even though a
     * DDL statement was run within a transaction, the database will issue an implicit commit before and after
     * its execution.
     * {@code true} if mixed migrations should be allowed. {@code false} if an error should be thrown instead. (default: {@code false})
     */
    public Boolean mixed;

    /**
     * Whether to group all pending migrations together in the same transaction when applying them (only recommended for databases with support for DDL transactions).
     * {@code true} if migrations should be grouped. {@code false} if they should be applied individually instead. (default: {@code false})
     */
    public Boolean group;

    /**
     * The username that will be recorded in the schema history table as having applied the migration.
     * {@code null} for the current database user of the connection. (default: {@code null}).
     */
    public String installedBy;

    /**
     * Gradle configurations that will be added to the classpath for running Flyway tasks.
     * (default: <code>compile</code>, <code>runtime</code>, <code>testCompile</code>, <code>testRuntime</code>)
     * <p>Also configurable with Gradle or System Property: ${flyway.configurations}</p>
     */
    public String[] configurations;

    /**
     * Rules for the built-in error handler that let you override specific SQL states and errors codes in order to force
     * specific errors or warnings to be treated as debug messages, info messages, warnings or errors.
     * <p>Each error override has the following format: {@code STATE:12345:W}.
     * It is a 5 character SQL state (or * to match all SQL states), a colon,
     * the SQL error code (or * to match all SQL error codes), a colon and finally
     * the desired behavior that should override the initial one.</p>
     * <p>The following behaviors are accepted:</p>
     * <ul>
     * <li>{@code D} to force a debug message</li>
     * <li>{@code D-} to force a debug message, but do not show the original sql state and error code</li>
     * <li>{@code I} to force an info message</li>
     * <li>{@code I-} to force an info message, but do not show the original sql state and error code</li>
     * <li>{@code W} to force a warning</li>
     * <li>{@code W-} to force a warning, but do not show the original sql state and error code</li>
     * <li>{@code E} to force an error</li>
     * <li>{@code E-} to force an error, but do not show the original sql state and error code</li>
     * </ul>
     * <p>Example 1: to force Oracle stored procedure compilation issues to produce
     * errors instead of warnings, the following errorOverride can be used: {@code 99999:17110:E}</p>
     * <p>Example 2: to force SQL Server PRINT messages to be displayed as info messages (without SQL state and error
     * code details) instead of warnings, the following errorOverride can be used: {@code S0001:0:I-}</p>
     * <p>Example 3: to force all errors with SQL error code 123 to be treated as warnings instead,
     * the following errorOverride can be used: {@code *:123:W}</p>
     * <i>Flyway Teams only</i>
     * <p>Also configurable with Gradle or System Property: ${flyway.errorOverrides}</p>
     */
    public String[] errorOverrides;

    /**
     * The file where to output the SQL statements of a migration dry run. If the file specified is in a non-existent
     * directory, Flyway will create all directories and parent directories as needed.
     * Paths starting with s3: point to a bucket in AWS S3, which must exist. They are in the format s3:<bucket>(/optionalfolder/subfolder)/filename.sql
     * Paths starting with gcs: point to a bucket in Google Cloud Storage, which must exist. They are in the format gcs:<bucket>(/optionalfolder/subfolder)/filename.sql
     * <p>{@code null} to execute the SQL statements directly against the database. (default: {@code null})</p>
     * <i>Flyway Teams only</i>
     * <p>Also configurable with Gradle or System Property: ${flyway.dryRunOutput}</p>
     */
    public String dryRunOutput;

    /**
     * Whether to stream SQL migrations when executing them. Streaming doesn't load the entire migration in memory at
     * once. Instead each statement is loaded individually. This is particularly useful for very large SQL migrations
     * composed of multiple MB or even GB of reference data, as this dramatically reduces Flyway's memory consumption.
     * (default: {@code false}
     * <i>Flyway Teams only</i>
     * <p>Also configurable with Gradle or System Property: ${flyway.stream}</p>
     */
    public Boolean stream;

    /**
     * Whether to batch SQL statements when executing them. Batching can save up to 99 percent of network roundtrips by
     * sending up to 100 statements at once over the network to the database, instead of sending each statement
     * individually. This is particularly useful for very large SQL migrations composed of multiple MB or even GB of
     * reference data, as this can dramatically reduce the network overhead. This is supported for INSERT, UPDATE,
     * DELETE, MERGE and UPSERT statements. All other statements are automatically executed without batching.
     * (default: {@code false})
     * <i>Flyway Teams only</i>
     * <p>Also configurable with Gradle or System Property: ${flyway.batch}</p>
     */
    public Boolean batch;

    /**
     * Whether to Flyway's support for Oracle SQL*Plus commands should be activated.
     * (default: {@code false})
     * <i>Flyway Teams only</i>
     * <p>Also configurable with Gradle or System Property: ${flyway.oracle.sqlplus}</p>
     */
    public Boolean oracleSqlplus;

    /**
     * Whether Flyway should issue a warning instead of an error whenever it encounters an Oracle SQL*Plus statement
     * it doesn't yet support. (default: {@code false})
     * <i>Flyway Teams only</i>
     * <p>Also configurable with Gradle or System Property: ${flyway.oracle.sqlplusWarn}</p>
     */
    public Boolean oracleSqlplusWarn;

    /**
     * The location of your Oracle wallet, used to automatically sign in to your databases.
     *
     * <i>Flyway Teams only</i>
     * <p>Also configurable with Gradle or System Property: ${flyway.oracle.walletLocation}</p>
     */
    public String oracleWalletLocation;

    /**
     * Your Flyway license key (FL01...). Not yet a Flyway Teams Edition customer?
     * Request your <a href="https://flywaydb.org/download/">Flyway trial license key</a>
     * to try out Flyway Teams Edition features free for 30 days.
     * <i>Flyway Teams only</i>
     * <p>Also configurable with Gradle or System Property: ${flyway.licenseKey}</p>
     */
    public String licenseKey;

    /**
     * The maximum number of retries when trying to obtain a lock. (default: 50)
     */
    public Integer lockRetryCount;

    /**
     * The encoding of the external config files specified with the {@code flyway.configFiles} property. (default: UTF-8).
     * <p>Also configurable with Gradle or System Property: ${flyway.configFileEncoding}</p>
     */
    public String configFileEncoding;

    /**
     * Config files from which to load the Flyway configuration. The names of the individual properties match the ones you would
     * use as Gradle or System properties. The encoding of the files is defined by the
     * flyway.configFileEncoding property, which is UTF-8 by default. Relative paths are relative to the project root.
     * <p>Also configurable with Gradle or System Property: ${flyway.configFiles}</p>
     */
    public String[] configFiles;

    /**
     * The working directory to consider when dealing with relative paths for both config files and locations.
     * (default: basedir, the directory where the POM resides)
     * <p>Also configurable with Gradle or System Property: ${flyway.workingDirectory}</p>
     */
    public String workingDirectory;

    /**
     * Whether to fail if a location specified in the flyway.locations option doesn't exist
     *
     * @return @{code true} to fail (default: {@code false})
     */
    public boolean failOnMissingLocations;

    /**
     * The configuration for Vault secrets manager.
     * You will need to configure the following fields:
     * <ul>
     *  <li>vaultUrl: The REST API URL of your Vault server - https://flywaydb.org/documentation/configuration/parameters/vaultUrl</li>
     *  <li>vaultToken: The Vault token required to access your secrets - https://flywaydb.org/documentation/configuration/parameters/vaultToken</li>
     *  <li>vaultSecrets: A list of paths to secrets in Vault that contain Flyway configurations - https://flywaydb.org/documentation/configuration/parameters/vaultSecrets</li>
     * </ul>
     * <i>Flyway Teams only</i>
     */
    public VaultConfiguration vaultConfiguration;

    /**
     * The configuration for DAPR Secrets Store.
     * You will need to configure the following fields:
     * <ul>
     *  <li>daprUrl: The REST API URL of your Dapr application sidecar - https://flywaydb.org/documentation/configuration/parameters/daprUrl</li>
     *  <li>daprSecrets: A list of paths to secrets in Dapr that contain Flyway configurations - https://flywaydb.org/documentation/configuration/parameters/daprSecrets</li>
     * </ul>
     * <i>Flyway Teams only</i>
     */
    public DaprConfiguration daprConfiguration;

    /**
     * The configuration for Google Cloud Secret Manager.
     * You will need to configure the following fields:
     * <ul>
     *  <li>gcsmProject: The Project which contains your secrets - https://flywaydb.org/documentation/configuration/parameters/gcsmProject</li>
     *  <li>gcsmSecrets: A list of secrets in GCSM that contain Flyway configurations - https://flywaydb.org/documentation/configuration/parameters/gcsmSecrets</li>
     * </ul>
     * <i>Flyway Teams only</i>
     */
    public GcsmConfiguration gcsmConfiguration;

    public AbstractFlywayTask() {
        super();
        setGroup("Flyway");
        extension = (FlywayExtension) getProject().getExtensions().getByName("flyway");
    }

    @TaskAction
    public Object runTask() {
        try {
            Map<String, String> envVars = ConfigUtils.environmentVariablesToPropertyMap();

            Set<URL> extraURLs = new HashSet<>();
            if (isJavaProject()) {
                addClassesAndResourcesDirs(extraURLs);
            }

            addConfigurationArtifacts(determineConfigurations(envVars), extraURLs);
            
            ClassLoader classLoader = new URLClassLoader(
                    extraURLs.toArray(new URL[0]),
                    getProject().getBuildscript().getClassLoader());

            Map<String, String> config = createFlywayConfig(envVars);
            ConfigUtils.dumpConfiguration(config);

            Flyway flyway = Flyway.configure(classLoader).configuration(config).load();
            Object result = run(flyway);
            ((DriverDataSource) flyway.getConfiguration().getDataSource()).shutdownDatabase();
            return result;
        } catch (Exception e) {
            throw new FlywayException(collectMessages(e, "Error occurred while executing " + getName()), e);
        }
    }

    private void addClassesAndResourcesDirs(Set<URL> extraURLs) throws MalformedURLException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        JavaPluginConvention plugin = getProject().getConvention().getPlugin(JavaPluginConvention.class);

        for (SourceSet sourceSet : plugin.getSourceSets()) {
            try {
                FileCollection classesDirs = sourceSet.getOutput().getClassesDirs();
                for (File directory : classesDirs.getFiles()) {
                    URL classesUrl = directory.toURI().toURL();
                    getLogger().debug("Adding directory to Classpath: " + classesUrl);
                    extraURLs.add(classesUrl);
                }
            } catch (NoSuchMethodError ex) {
                getLogger().debug("Falling back to legacy getClassesDir method");

                // try legacy gradle 3.0 method instead
                @SuppressWarnings("JavaReflectionMemberAccess")
                Method getClassesDir = SourceSetOutput.class.getMethod("getClassesDir");

                File classesDir = (File) getClassesDir.invoke(sourceSet.getOutput());
                URL classesUrl = classesDir.toURI().toURL();

                getLogger().debug("Adding directory to Classpath: " + classesUrl);
                extraURLs.add(classesUrl);
            }

            URL resourcesUrl = sourceSet.getOutput().getResourcesDir().toURI().toURL();
            getLogger().debug("Adding directory to Classpath: " + resourcesUrl);
            extraURLs.add(resourcesUrl);
        }
    }

    private void addConfigurationArtifacts(String[] configurations, Set<URL> urls) throws IOException {
        for (String configuration : configurations) {
            getLogger().debug("Adding configuration to classpath: " + configuration);
            ResolvedConfiguration resolvedConfiguration =
                    getProject().getConfigurations().getByName(configuration).getResolvedConfiguration();
            for (ResolvedArtifact artifact : resolvedConfiguration.getResolvedArtifacts()) {
                URL artifactUrl = artifact.getFile().toURI().toURL();
                getLogger().debug("Adding artifact to classpath: " + artifactUrl);
                urls.add(artifactUrl);
            }
        }
    }

    private String[] determineConfigurations(Map<String, String> envVars) {
        if (envVars.containsKey(ConfigUtils.CONFIGURATIONS)) {
            return StringUtils.tokenizeToStringArray(envVars.get(ConfigUtils.CONFIGURATIONS), ",");
        }
        if (System.getProperties().containsKey(ConfigUtils.CONFIGURATIONS)) {
            return StringUtils.tokenizeToStringArray(System.getProperties().getProperty(ConfigUtils.CONFIGURATIONS), ",");
        }
        if (configurations != null) {
            return configurations;
        }
        if (extension.configurations != null) {
            return extension.configurations;
        }
        if (isJavaProject()) {
            if (getProject().getGradle().getGradleVersion().startsWith("3")) {
                return DEFAULT_CONFIGURATIONS_GRADLE3;
            }
            return DEFAULT_CONFIGURATIONS_GRADLE45;
        } else {
            return new String[0];
        }
    }

    /**
     * Executes the task's custom behavior.
     *
     * @param flyway The Flyway instance to use.
     * @return The result of the task.
     */
    protected abstract Object run(Flyway flyway);

    private Map<String, String> createFlywayConfig(Map<String, String> envVars) {
        Map<String, String> conf = new HashMap<>();

        addLocationsToConfig(conf);
        addConfigFromProperties(conf, loadConfigurationFromDefaultConfigFiles(envVars));

        putIfSet(conf, ConfigUtils.DRIVER, driver, extension.driver);
        putIfSet(conf, ConfigUtils.URL, url, extension.url);
        putIfSet(conf, ConfigUtils.USER, user, extension.user);
        putIfSet(conf, ConfigUtils.PASSWORD, password, extension.password);
        putIfSet(conf, ConfigUtils.CONNECT_RETRIES, connectRetries, extension.connectRetries);
        putIfSet(conf, ConfigUtils.CONNECT_RETRIES_INTERVAL, connectRetriesInterval, extension.connectRetriesInterval);
        putIfSet(conf, ConfigUtils.INIT_SQL, initSql, extension.initSql);
        putIfSet(conf, ConfigUtils.TABLE, table, extension.table);
        putIfSet(conf, ConfigUtils.TABLESPACE, tablespace, extension.tablespace);
        putIfSet(conf, ConfigUtils.BASELINE_VERSION, baselineVersion, extension.baselineVersion);
        putIfSet(conf, ConfigUtils.BASELINE_DESCRIPTION, baselineDescription, extension.baselineDescription);
        putIfSet(conf, ConfigUtils.SQL_MIGRATION_PREFIX, sqlMigrationPrefix, extension.sqlMigrationPrefix);
        putIfSet(conf, ConfigUtils.STATE_SCRIPT_PREFIX, stateScriptPrefix, extension.stateScriptPrefix);
        putIfSet(conf, ConfigUtils.UNDO_SQL_MIGRATION_PREFIX, undoSqlMigrationPrefix, extension.undoSqlMigrationPrefix);
        putIfSet(conf, ConfigUtils.REPEATABLE_SQL_MIGRATION_PREFIX, repeatableSqlMigrationPrefix, extension.repeatableSqlMigrationPrefix);
        putIfSet(conf, ConfigUtils.SQL_MIGRATION_SEPARATOR, sqlMigrationSeparator, extension.sqlMigrationSeparator);
        putIfSet(conf, ConfigUtils.SQL_MIGRATION_SUFFIXES, StringUtils.arrayToCommaDelimitedString(sqlMigrationSuffixes), StringUtils.arrayToCommaDelimitedString(extension.sqlMigrationSuffixes));
        putIfSet(conf, ConfigUtils.MIXED, mixed, extension.mixed);
        putIfSet(conf, ConfigUtils.GROUP, group, extension.group);
        putIfSet(conf, ConfigUtils.INSTALLED_BY, installedBy, extension.installedBy);
        putIfSet(conf, ConfigUtils.ENCODING, encoding, extension.encoding);
        putIfSet(conf, ConfigUtils.DETECT_ENCODING, detectEncoding, extension.detectEncoding);
        putIfSet(conf, ConfigUtils.LOCK_RETRY_COUNT, lockRetryCount, extension.lockRetryCount);
        putIfSet(conf, ConfigUtils.PLACEHOLDER_REPLACEMENT, placeholderReplacement, extension.placeholderReplacement);
        putIfSet(conf, ConfigUtils.PLACEHOLDER_PREFIX, placeholderPrefix, extension.placeholderPrefix);
        putIfSet(conf, ConfigUtils.PLACEHOLDER_SUFFIX, placeholderSuffix, extension.placeholderSuffix);
        putIfSet(conf, ConfigUtils.SCRIPT_PLACEHOLDER_PREFIX, scriptPlaceholderPrefix, extension.scriptPlaceholderPrefix);
        putIfSet(conf, ConfigUtils.SCRIPT_PLACEHOLDER_SUFFIX, scriptPlaceholderSuffix, extension.scriptPlaceholderSuffix);
        putIfSet(conf, ConfigUtils.TARGET, target, extension.target);
        putIfSet(conf, ConfigUtils.CHERRY_PICK, StringUtils.arrayToCommaDelimitedString(cherryPick), StringUtils.arrayToCommaDelimitedString(extension.cherryPick));
        putIfSet(conf, ConfigUtils.LOGGERS, StringUtils.arrayToCommaDelimitedString(loggers), StringUtils.arrayToCommaDelimitedString(extension.loggers));
        putIfSet(conf, ConfigUtils.OUT_OF_ORDER, outOfOrder, extension.outOfOrder);
        putIfSet(conf, ConfigUtils.SKIP_EXECUTING_MIGRATIONS, skipExecutingMigrations, extension.skipExecutingMigrations);
        putIfSet(conf, ConfigUtils.OUTPUT_QUERY_RESULTS, outputQueryResults, extension.outputQueryResults);
        putIfSet(conf, ConfigUtils.VALIDATE_ON_MIGRATE, validateOnMigrate, extension.validateOnMigrate);
        putIfSet(conf, ConfigUtils.CLEAN_ON_VALIDATION_ERROR, cleanOnValidationError, extension.cleanOnValidationError);
        putIfSet(conf, ConfigUtils.IGNORE_MISSING_MIGRATIONS, ignoreMissingMigrations, extension.ignoreMissingMigrations);
        putIfSet(conf, ConfigUtils.IGNORE_IGNORED_MIGRATIONS, ignoreIgnoredMigrations, extension.ignoreIgnoredMigrations);
        putIfSet(conf, ConfigUtils.IGNORE_PENDING_MIGRATIONS, ignorePendingMigrations, extension.ignorePendingMigrations);
        putIfSet(conf, ConfigUtils.IGNORE_FUTURE_MIGRATIONS, ignoreFutureMigrations, extension.ignoreFutureMigrations);
        putIfSet(conf, ConfigUtils.IGNORE_MIGRATION_PATTERNS, StringUtils.arrayToCommaDelimitedString(ignoreMigrationPatterns), StringUtils.arrayToCommaDelimitedString(extension.ignoreMigrationPatterns));
        putIfSet(conf, ConfigUtils.VALIDATE_MIGRATION_NAMING, validateMigrationNaming, extension.validateMigrationNaming);
        putIfSet(conf, ConfigUtils.CLEAN_DISABLED, cleanDisabled, extension.cleanDisabled);
        putIfSet(conf, ConfigUtils.BASELINE_ON_MIGRATE, baselineOnMigrate, extension.baselineOnMigrate);
        putIfSet(conf, ConfigUtils.SKIP_DEFAULT_RESOLVERS, skipDefaultResolvers, extension.skipDefaultResolvers);
        putIfSet(conf, ConfigUtils.SKIP_DEFAULT_CALLBACKS, skipDefaultCallbacks, extension.skipDefaultCallbacks);
        putIfSet(conf, ConfigUtils.DEFAULT_SCHEMA, defaultSchema, extension.defaultSchema);
        putIfSet(conf, ConfigUtils.CREATE_SCHEMAS, createSchemas, extension.createSchemas);
        putIfSet(conf, ConfigUtils.FAIL_ON_MISSING_LOCATIONS, failOnMissingLocations, extension.failOnMissingLocations);

        putIfSet(conf, ConfigUtils.SCHEMAS, StringUtils.arrayToCommaDelimitedString(schemas), StringUtils.arrayToCommaDelimitedString(extension.schemas));
        putIfSet(conf, ConfigUtils.RESOLVERS, StringUtils.arrayToCommaDelimitedString(resolvers), StringUtils.arrayToCommaDelimitedString(extension.resolvers));
        putIfSet(conf, ConfigUtils.CALLBACKS, StringUtils.arrayToCommaDelimitedString(callbacks), StringUtils.arrayToCommaDelimitedString(extension.callbacks));
        putIfSet(conf, ConfigUtils.ERROR_OVERRIDES, StringUtils.arrayToCommaDelimitedString(errorOverrides), StringUtils.arrayToCommaDelimitedString(extension.errorOverrides));

        putIfSet(conf, ConfigUtils.DRYRUN_OUTPUT, dryRunOutput, extension.dryRunOutput);
        putIfSet(conf, ConfigUtils.STREAM, stream, extension.stream);
        putIfSet(conf, ConfigUtils.BATCH, batch, extension.batch);

        putIfSet(conf, ConfigUtils.ORACLE_SQLPLUS, oracleSqlplus, extension.oracleSqlplus);
        putIfSet(conf, ConfigUtils.ORACLE_SQLPLUS_WARN, oracleSqlplusWarn, extension.oracleSqlplusWarn);
        putIfSet(conf, ConfigUtils.ORACLE_WALLET_LOCATION, oracleWalletLocation, extension.oracleWalletLocation);

        putIfSet(conf, ConfigUtils.LICENSE_KEY, licenseKey, extension.licenseKey);

        if (extension.placeholders != null) {
            for (Map.Entry<Object, Object> entry : extension.placeholders.entrySet()) {
                conf.put(ConfigUtils.PLACEHOLDERS_PROPERTY_PREFIX + entry.getKey().toString(), entry.getValue().toString());
            }
        }
        if (placeholders != null) {
            for (Map.Entry<Object, Object> entry : placeholders.entrySet()) {
                conf.put(ConfigUtils.PLACEHOLDERS_PROPERTY_PREFIX + entry.getKey().toString(), entry.getValue().toString());
            }
        }

        if (extension.jdbcProperties != null) {
            for (Map.Entry<Object, Object> entry : extension.jdbcProperties.entrySet()) {
                conf.put(ConfigUtils.JDBC_PROPERTIES_PREFIX + entry.getKey().toString(), entry.getValue().toString());
            }
        }
        if (jdbcProperties != null) {
            for (Map.Entry<Object, Object> entry : jdbcProperties.entrySet()) {
                conf.put(ConfigUtils.JDBC_PROPERTIES_PREFIX + entry.getKey().toString(), entry.getValue().toString());
            }
        }

        if (extension.vaultConfiguration != null){
            extension.vaultConfiguration.extract(conf);
        }
        if (vaultConfiguration != null){
            vaultConfiguration.extract(conf);
        }
        if (extension.daprConfiguration != null) {
            extension.daprConfiguration.extract(conf);
        }
        if (daprConfiguration != null){
            daprConfiguration.extract(conf);
        }
        if (extension.gcsmConfiguration != null) {
            extension.gcsmConfiguration.extract(conf);
        }
        if (gcsmConfiguration != null) {
            gcsmConfiguration.extract(conf);
        }

        addConfigFromProperties(conf, getProject().getProperties());
        addConfigFromProperties(conf, loadConfigurationFromConfigFiles(getWorkingDirectory(), envVars));
        addConfigFromProperties(conf, envVars);
        addConfigFromProperties(conf, System.getProperties());
        conf.putAll(ConfigUtils.loadConfigurationFromSecretsManagers(conf));
        removeGradlePluginSpecificPropertiesToAvoidWarnings(conf);

        return conf;
    }

    private void addLocationsToConfig(Map<String, String> conf) {
        File workingDirectory = getWorkingDirectory();

        conf.put(ConfigUtils.LOCATIONS, Location.FILESYSTEM_PREFIX + workingDirectory + "/src/main/resources/db/migration");

        String[] locationsToAdd = getLocations();

        if (locationsToAdd != null) {
            for (int i = 0; i < locationsToAdd.length; i++) {
                if (locationsToAdd[i].startsWith(Location.FILESYSTEM_PREFIX)) {
                    String newLocation = locationsToAdd[i].substring(Location.FILESYSTEM_PREFIX.length());
                    File file = new File(newLocation);
                    if (!file.isAbsolute()) {
                        file = new File(workingDirectory, newLocation);
                    }
                    locationsToAdd[i] = Location.FILESYSTEM_PREFIX + file.getAbsolutePath();
                }
            }
        }

        putIfSet(conf, ConfigUtils.LOCATIONS, StringUtils.arrayToCommaDelimitedString(locationsToAdd));
    }

    private String[] getLocations() {
        // To maintain override order, return configured values before extension values
        if (locations != null) {
            return locations;
        }

        if (extension.locations != null) {
            return extension.locations;
        }

        return null;
    }

    private File getWorkingDirectory() {
        // To maintain override order, return extension value first if present
        if (extension.workingDirectory != null) {
            return new File(extension.workingDirectory);
        }

        if (workingDirectory != null) {
            return new File(workingDirectory);
        }

        return new File(getProject().getProjectDir().getAbsolutePath());
    }

    /**
     * Load properties from the config files (if specified).
     *
     * @param workingDirectory The working directory to use.
     * @param envVars The environment variables converted to Flyway properties.
     * @return The properties.
     */
    private Map<String, String> loadConfigurationFromConfigFiles(File workingDirectory, Map<String, String> envVars) {
        String encoding = determineConfigurationFileEncoding(envVars);

        Map<String, String> conf = new HashMap<>();
        for (File configFile : determineConfigFiles(workingDirectory, envVars)) {
            conf.putAll(ConfigUtils.loadConfigurationFile(configFile, encoding, true));
        }
        return conf;
    }

    /**
     * Load properties from the default config files (if available).
     *
     * @param envVars The environment variables converted to Flyway properties.
     * @return The properties.
     */
    private Map<String, String> loadConfigurationFromDefaultConfigFiles(Map<String, String> envVars) {
        String encoding = determineConfigurationFileEncoding(envVars);
        File configFile = new File(System.getProperty("user.home") + "/" + ConfigUtils.CONFIG_FILE_NAME);

        return new HashMap<>(ConfigUtils.loadConfigurationFile(configFile, encoding, false));
    }

    /**
     * Determines the encoding to use for loading the configuration files.
     *
     * @param envVars The environment variables converted to Flyway properties.
     * @return The encoding. (default: UTF-8)
     */
    private String determineConfigurationFileEncoding(Map<String, String> envVars) {
        if (envVars.containsKey(ConfigUtils.CONFIG_FILE_ENCODING)) {
            return envVars.get(ConfigUtils.CONFIG_FILE_ENCODING);
        }
        if (System.getProperties().containsKey(ConfigUtils.CONFIG_FILE_ENCODING)) {
            return System.getProperties().getProperty(ConfigUtils.CONFIG_FILE_ENCODING);
        }
        if (configFileEncoding != null) {
            return configFileEncoding;
        }
        if (extension.configFileEncoding != null) {
            return extension.configFileEncoding;
        }
        return "UTF-8";
    }

    /**
     * Determines the files to use for loading the configuration.
     *
     * @param workingDirectory The working directory to use.
     * @param envVars The environment variables converted to Flyway properties.
     * @return The configuration files.
     */
    private List<File> determineConfigFiles(File workingDirectory, Map<String, String> envVars) {
        List<File> configFiles = new ArrayList<>();

        if (envVars.containsKey(ConfigUtils.CONFIG_FILES)) {
            for (String file : StringUtils.tokenizeToStringArray(envVars.get(ConfigUtils.CONFIG_FILES), ",")) {
                configFiles.add(toFile(workingDirectory, file));
            }
            return configFiles;
        }

        if (System.getProperties().containsKey(ConfigUtils.CONFIG_FILES)) {
            for (String file : StringUtils.tokenizeToStringArray(System.getProperties().getProperty(ConfigUtils.CONFIG_FILES), ",")) {
                configFiles.add(toFile(workingDirectory, file));
            }
            return configFiles;
        }

        if (getProject().getProperties().containsKey(ConfigUtils.CONFIG_FILES)) {
            for (String file : StringUtils.tokenizeToStringArray(String.valueOf(getProject().getProperties().get(ConfigUtils.CONFIG_FILES)), ",")) {
                configFiles.add(toFile(workingDirectory, file));
            }
            return configFiles;
        }

        if (this.configFiles != null) {
            for (String file : this.configFiles) {
                configFiles.add(toFile(workingDirectory, file));
            }
            return configFiles;
        }

        if (extension.configFiles != null) {
            for (String file : extension.configFiles) {
                configFiles.add(toFile(workingDirectory, file));
            }
            return configFiles;
        }

        return configFiles;
    }

    /**
     * Converts this file name into a file, adjusting relative paths if necessary to make them relative to the pom.
     *
     * @param workingDirectory  The working directory to use.
     * @param fileName The name of the file, relative or absolute.
     * @return The resulting file.
     */
    private File toFile(File workingDirectory, String fileName) {
        File file = new File(fileName);
        if (file.isAbsolute()) {
            return file;
        }
        return new File(workingDirectory, fileName);
    }

    /**
     * Filters these properties to remove the Flyway Gradle Plugin-specific ones to avoid warnings.
     *
     * @param conf The properties to filter.
     */
    private static void removeGradlePluginSpecificPropertiesToAvoidWarnings(Map<String, String> conf) {
        conf.remove(ConfigUtils.CONFIG_FILES);
        conf.remove(ConfigUtils.CONFIG_FILE_ENCODING);
        conf.remove(ConfigUtils.CONFIGURATIONS);
        conf.remove("flyway.version");
        conf.remove("flyway.workingDirectory");
    }

    private static void addConfigFromProperties(Map<String, String> config, Properties properties) {
        for (String prop : properties.stringPropertyNames()) {
            if (prop.startsWith("flyway.")) {
                config.put(prop, properties.getProperty(prop));
            }
        }
    }

    private static void addConfigFromProperties(Map<String, String> config, Map<String, ?> properties) {
        for (String prop : properties.keySet()) {
            if (prop.startsWith("flyway.")) {
                config.put(prop, properties.get(prop).toString());
            }
        }
    }

    /**
     * Collect error messages from the stack trace.
     *
     * @param throwable Throwable instance from which the message should be built.
     * @param message   The message to which the error message will be appended.
     * @return A String containing the composed messages.
     */
    private String collectMessages(Throwable throwable, String message) {
        if (throwable != null) {
            message += "\n" + throwable.getMessage();
            return collectMessages(throwable.getCause(), message);
        }
        return message;
    }

    private boolean isJavaProject() {
        return getProject().getPluginManager().hasPlugin("java");
    }
}