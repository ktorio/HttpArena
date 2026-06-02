package com.httparena

import com.httparena.DbResponse.Companion.toResponse
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.*
import kotlinx.html.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

fun main() {
    val appData = AppData()
    println("Ktor HttpArena server starting on :8080 (HTTP/1.1) and :8443 (HTTPS/HTTP+2)")

    val environment = applicationEnvironment {}
    val module: Application.() -> Unit = {
        install(DefaultHeaders) {
            header("Server", "ktor")
        }
        install(Compression) {
            gzip()
        }
        install(ContentNegotiation) {
            json(appData.json)
        }
        install(WebSockets)

        configureRouting(appData)
    }
    val server = embeddedServer(Netty, environment, {
        enableHttp2 = true

        connector {
            port = 8080
            host = "0.0.0.0"
        }
        appData.keystore?.let { keyStore ->
            sslConnector(
                keyStore = keyStore,
                keyAlias = KEY_ALIAS,
                keyStorePassword = { KEYSTORE_PASSWORD },
                privateKeyPassword = { KEYSTORE_PASSWORD }
            ) {
                port = 8081
                host = "0.0.0.0"
            }
            sslConnector(
                keyStore = keyStore,
                keyAlias = KEY_ALIAS,
                keyStorePassword = { KEYSTORE_PASSWORD },
                privateKeyPassword = { KEYSTORE_PASSWORD }
            ) {
                port = 8443
                host = "0.0.0.0"
            }
        }
    }, module)

    // Spin up a second server for H2C
    embeddedServer(Netty, environment, {
        enableH2c = true

        connector {
            port = 8082
            host = "0.0.0.0"
        }
    }) {
        // Reject any non-HTTP/2 request hitting the H2C connector
        intercept(ApplicationCallPipeline.Plugins) {
            val version = call.request.httpVersion
            if (!version.startsWith("HTTP/2")) {
                call.response.headers.append(HttpHeaders.Upgrade, "h2c")
                call.response.headers.append(HttpHeaders.Connection, "Upgrade")
                call.respond(HttpStatusCode.UpgradeRequired, "HTTP/2 (h2c) required")
                finish()
                return@intercept
            }
        }
        // Import the same endpoints for this server
        module()

    }.start(wait = false)

    server.start(wait = true)
}

private fun Application.configureRouting(appData: AppData) {

    fun ApplicationCall.sumQueryParams(): Long =
        request.queryParameters.entries().sumOf { (_, v) ->
            v.sumOf { it.toLongOrNull() ?: 0L }
        }

    routing {
        /**
         * Pipelined
         * https://www.http-arena.com/docs/test-profiles/h1/isolated/pipelined/
         */
        get("/pipeline") {
            call.respondText("ok", ContentType.Text.Plain)
        }

        /**
         * Baseline 1.1
         * https://www.http-arena.com/docs/test-profiles/h1/isolated/baseline/
         */
        get("/baseline11") {
            call.respondText(
                call.sumQueryParams().toString(),
                ContentType.Text.Plain
            )
        }

        /**
         * Baseline 1.1
         * https://www.http-arena.com/docs/test-profiles/h1/isolated/baseline/
         */
        post("/baseline11") {
            val sum = call.sumQueryParams()
            val body = call.receiveText().trim().toLongOrNull() ?: run {
                call.respondText(sum.toString(), ContentType.Text.Plain)
                return@post
            }
            call.respondText(
                (sum + body).toString(),
                ContentType.Text.Plain
            )
        }

        /**
         * Baseline 2
         * https://www.http-arena.com/docs/test-profiles/h1/isolated/baseline/
         */
        get("/baseline2") {
            call.respondText(
                call.sumQueryParams().toString(),
                ContentType.Text.Plain
            )
        }

        /**
         * JSON processing
         * https://www.http-arena.com/docs/test-profiles/h1/isolated/json-processing/
         * https://www.http-arena.com/docs/test-profiles/h1/isolated/json-tls/
         * https://www.http-arena.com/docs/test-profiles/h1/isolated/json-compressed/
         */
        get("/json/{count}") {
            if (appData.dataset.isEmpty()) {
                call.respondText("Dataset not loaded", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                return@get
            }
            var count = call.pathParameters["count"]?.toIntOrNull() ?: 0
            if (count < 0) count = 0
            if (count > appData.dataset.size) count = appData.dataset.size
            val m = call.request.queryParameters["m"]?.toIntOrNull() ?: 1
            val processed = appData.dataset.take(count).map { d ->
                ProcessedItem(
                    id = d.id, name = d.name, category = d.category,
                    price = d.price, quantity = d.quantity, active = d.active,
                    tags = d.tags, rating = d.rating,
                    total = d.price.toLong() * d.quantity * m
                )
            }
            call.respond(JsonResponse(items = processed, count = count))
        }

        /**
         * Async DB
         * https://www.http-arena.com/docs/test-profiles/h1/isolated/async-database/
         */
        get("/async-db") {
            val min = call.request.queryParameters["min"]?.toIntOrNull() ?: 10
            val max = call.request.queryParameters["max"]?.toIntOrNull() ?: 50
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 50)
            try {
                val items = suspendTransaction(appData.postgres, readOnly = true) {
                    with(ItemTable) {
                        selectAll()
                            .where { price.between(min, max) }
                            .limit(limit)
                            .map(::toDbItem)
                            .toList()
                    }
                }
                call.respond(items.toResponse())
            } catch (e: Exception) {
                log.error("Failed to load items from DB", e)
                call.respondBytes("{\"items\":[],\"count\":0}".toByteArray(), ContentType.Application.Json)
            }
        }

        /**
         * Upload 20MB
         * https://www.http-arena.com/docs/test-profiles/h1/isolated/upload/
         */
        post("/upload") {
            val channel = call.request.receiveChannel()
            val totalBytes = channel.readTo(DevNull)
            call.respondText(
                totalBytes.toString(),
                ContentType.Text.Plain
            )
        }

        /**
         * Static files
         * https://www.http-arena.com/docs/test-profiles/h1/isolated/static/
         */
        staticFiles("/static", File("/data/static")) {
            preCompressed(CompressedFileType.BROTLI, CompressedFileType.GZIP)
        }

        /**
         * Echo WebSocket
         * https://www.http-arena.com/docs/test-profiles/ws/
         */
        webSocket("/ws") {
            for (message in incoming)
                send(message)
        }

        /**
         * CRUD (REST API) — paginated list, cached single-item read, upsert create, partial update.
         * https://www.http-arena.com/docs/test-profiles/h1/isolated/crud/
         */
        crudEndpoints(appData)

        /**
         * Fortunes (template-engine benchmark) — kotlinx.html DSL.
         * https://www.http-arena.com/docs/test-profiles/h1/isolated/fortunes/
         */
        get("/fortunes") {
            val fortunes = mutableListOf<Fortune>()
            try {
                suspendTransaction(appData.postgres, readOnly = true) {
                    FortuneTable.selectAll()
                        .map(FortuneTable::toFortune)
                        .toList(fortunes)
                }
            } catch (e: Exception) {
                log.error("Failed to load fortunes from DB", e)
                call.respond(HttpStatusCode.InternalServerError, "fortunes failed")
                return@get
            }
            fortunes.add(RUNTIME_FORTUNE)
            fortunes.sortBy { it.message }

            call.respondHtml(HttpStatusCode.OK) {
                head { title { +"Fortunes" } }
                body {
                    table {
                        tr {
                            th { +"id" }
                            th { +"message" }
                        }
                        for ((id, message) in fortunes) {
                            tr {
                                td { +id.toString() }
                                td { +message }
                            }
                        }
                    }
                }
            }
        }

    }
}

fun Route.crudEndpoints(appData: AppData, log: Logger = LoggerFactory.getLogger("crudRoutes")): Route =
    route("/crud/items") {
        get {
            val categoryParam = call.request.queryParameters["category"] ?: "electronics"
            val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 50)
            val offset = (page - 1).toLong() * limit

            try {
                val items = suspendTransaction(appData.postgres, readOnly = true) {
                    ItemTable.selectAll()
                        .where { ItemTable.category eq categoryParam }
                        .orderBy(ItemTable.id, SortOrder.ASC)
                        .limit(limit).offset(offset)
                        .map(ItemTable::toDbItem)
                        .toList()
                }
                call.respond(CrudListResponse(items = items, total = items.size, page = page, limit = limit))
            } catch (e: Exception) {
                log.error("CRUD list failed", e)
                call.respond(HttpStatusCode.InternalServerError, "list failed")
            }
        }

        get("{id}") {
            val id = call.pathParameters["id"]?.toUIntOrNull() ?: run {
                call.respondText("bad id", status = HttpStatusCode.BadRequest)
                return@get
            }

            val cached = appData.crudCache.get(id)
            if (cached != null) {
                call.response.headers.append("X-Cache", "HIT")
                call.respondBytes(cached, ContentType.Application.Json)
                return@get
            }

            try {
                val row = suspendTransaction(appData.postgres, readOnly = true) {
                    ItemTable.selectAll()
                        .where { ItemTable.id eq id }
                        .limit(1)
                        .map(ItemTable::toDbItem)
                        .firstOrNull()
                }
                if (row == null) {
                    call.respondText("not found", status = HttpStatusCode.NotFound)
                    return@get
                }
                val body = appData.json.encodeToString(row).toByteArray()
                appData.crudCache.put(id, body)
                call.response.headers.append("X-Cache", "MISS")
                call.respondBytes(body, ContentType.Application.Json)
            } catch (e: Exception) {
                log.error("CRUD read failed", e)
                call.respond(HttpStatusCode.InternalServerError, "read failed")
            }
        }

        post {
            val req = try {
                call.receive<CrudCreateRequest>()
            } catch (_: Exception) {
                call.respondText("invalid body", status = HttpStatusCode.UnprocessableEntity)
                return@post
            }
            try {
                suspendTransaction(appData.postgres) {
                    ItemTable.upsert(
                        keys = arrayOf(ItemTable.id),
                        onUpdateExclude = listOf(ItemTable.ratingScore, ItemTable.ratingCount),
                    ) {
                        it[id] = req.id
                        it[name] = req.name
                        it[category] = req.category
                        it[price] = req.price
                        it[quantity] = req.quantity
                        it[active] = req.active
                        it[tags] = req.tags
                        it[ratingScore] = 0
                        it[ratingCount] = 0
                    }
                }
                appData.crudCache.invalidate(req.id)
                val response = DbItem(
                    id = req.id, name = req.name, category = req.category,
                    price = req.price, quantity = req.quantity, active = req.active,
                    tags = req.tags, rating = RatingInfo(0, 0)
                )
                call.respond(HttpStatusCode.Created, response)
            } catch (e: Exception) {
                log.error("CRUD create failed", e)
                call.respond(HttpStatusCode.InternalServerError, "create failed")
            }
        }

        put("{id}") {
            val id = call.pathParameters["id"]?.toUIntOrNull() ?: run {
                call.respondText("bad id", status = HttpStatusCode.BadRequest)
                return@put
            }
            val req = try {
                call.receive<CrudUpdateRequest>()
            } catch (_: Exception) {
                call.respondText("invalid body", status = HttpStatusCode.UnprocessableEntity)
                return@put
            }
            try {
                val updated = suspendTransaction(appData.postgres, readOnly = false) {
                    val rows = ItemTable.update({ ItemTable.id eq id }) { stmt ->
                        req.name?.let { v -> stmt[ItemTable.name] = v }
                        req.price?.let { v -> stmt[ItemTable.price] = v }
                        req.quantity?.let { v -> stmt[ItemTable.quantity] = v }
                    }
                    if (rows == 0) {
                        null
                    } else {
                        ItemTable.selectAll()
                            .where { ItemTable.id eq id }
                            .limit(1)
                            .map(ItemTable::toDbItem)
                            .firstOrNull()
                    }
                }
                appData.crudCache.invalidate(id)
                if (updated == null) {
                    call.respondText("not found", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(HttpStatusCode.OK, updated)
                }
            } catch (e: Exception) {
                log.error("CRUD update failed", e)
                call.respond(HttpStatusCode.InternalServerError, "update failed")
            }
        }
    }
