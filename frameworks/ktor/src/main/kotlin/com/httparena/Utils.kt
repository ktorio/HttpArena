package com.httparena

import io.ktor.utils.io.core.discard
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.IsolationLevel
import io.r2dbc.spi.ValidationDepth
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import java.io.File
import java.net.URI
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache entry holding pre-serialized JSON bytes and an absolute expiration time
 * (in nanos from [System.nanoTime]).  Used by the CRUD single-item read endpoint.
 */
class CacheEntry(val body: ByteArray, val expiresAt: Long)

/**
 * Simple in-process cache-aside with 200 ms absolute TTL for CRUD single-item reads.
 * Stale entries are removed lazily on access.
 */
class CrudCache(private val ttlMillis: Long = 200) {
    private val map = ConcurrentHashMap<UInt, CacheEntry>()

    fun get(id: UInt): ByteArray? {
        val entry = map[id] ?: return null
        if (entry.expiresAt <= System.nanoTime()) {
            map.remove(id, entry)
            return null
        }
        return entry.body
    }

    fun put(id: UInt, body: ByteArray) {
        val expiresAt = System.nanoTime() + ttlMillis * 1_000_000L
        map[id] = CacheEntry(body, expiresAt)
    }

    fun invalidate(id: UInt) {
        map.remove(id)
    }
}

object DevNull : RawSink {
    override fun close() {}
    override fun flush() {}
    override fun write(source: Buffer, byteCount: Long) {
        source.discard(byteCount)
    }
}

val RUNTIME_FORTUNE = Fortune(
    id = 0,
    message = "Additional fortune added at request time."
)

const val CERT_PATH = "/certs/server.crt"
const val KEY_PATH = "/certs/server.key"
const val KEY_ALIAS = "server"
val KEYSTORE_PASSWORD = CharArray(0)

class AppData {
    private val cpuCores = Runtime.getRuntime().availableProcessors()
    private val certFile = File(CERT_PATH)
    private val keyFile = File(KEY_PATH)
    private val datasetFile = File(System.getenv("DATASET_PATH") ?: "/data/dataset.json")

    val json = Json { ignoreUnknownKeys = true }

    /**
     * Cache-aside store used by the CRUD single-item read endpoint
     * (200 ms absolute TTL, in-process).
     */
    val crudCache = CrudCache(ttlMillis = 200)

    /**
     * Dataset from file.  Used in JSON endpoints.
     */
    var dataset: List<DatasetItem> = datasetFile.takeIf { it.exists() }?.let {
        json.decodeFromString(it.readText())
    } ?: emptyList()

    /**
     * PostgreSQL connection.  Used in async database endpoints.
     */
    val postgres: R2dbcDatabase? = System.getenv("DATABASE_URL")?.let { dbUrl ->
        runCatching {
            val uri = URI(dbUrl.replace("postgres://", "postgresql://"))
            val host = uri.host
            val port = if (uri.port > 0) uri.port else 5432
            val database = uri.path.removePrefix("/")
            val userInfo = uri.userInfo.split(":")

            val factory = PostgresqlConnectionFactory(
                PostgresqlConnectionConfiguration.builder()
                    .host(host)
                    .port(port)
                    .database(database)
                    .username(userInfo[0])
                    .password(if (userInfo.size > 1) userInfo[1] else "")
                    .build()
            )
            val maxConn = System.getenv("DATABASE_MAX_CONN")?.toIntOrNull() ?: (cpuCores * 2)
            val pool = ConnectionPool(
                ConnectionPoolConfiguration.builder(factory)
                    .initialSize(maxConn)
                    .maxSize(maxConn)
                    .validationQuery("")
                    .validationDepth(ValidationDepth.LOCAL)
                    .acquireRetry(0)
                    .build()
            )
            R2dbcDatabase.connect(
                connectionFactory = pool,
                databaseConfig = R2dbcDatabaseConfig.Builder().apply {
                    explicitDialect = PostgreSQLDialect()
                    defaultR2dbcIsolationLevel = IsolationLevel.READ_COMMITTED
                }
            )
        }
    }?.getOrNull()

    /**
     * Keystore for TLS.  Used in JSON TLS and JSON compressed endpoints.
     */
    val keystore: KeyStore? = certFile.takeIf { it.exists() }?.let { certFile ->
        val certs = CertificateFactory.getInstance("X.509")
            .generateCertificates(certFile.inputStream())
            .map { it as X509Certificate }
            .toTypedArray()

        val keyBytes = Base64.getMimeDecoder().decode(
            keyFile.readText()
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
        )
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(keyBytes))

        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(KEY_ALIAS, privateKey, KEYSTORE_PASSWORD, certs)
        }
    }
}
