/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.server.netty.http1

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.netty.buffer.*
import io.netty.channel.*
import io.netty.handler.codec.http.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

@OptIn(EngineAPI::class, InternalAPI::class)
internal class NettyHttp1ApplicationResponse constructor(
    call: NettyApplicationCall,
    context: ChannelHandlerContext,
    engineContext: CoroutineContext,
    userContext: CoroutineContext,
    val protocol: HttpVersion
) : NettyApplicationResponse(call, context, engineContext, userContext) {

    private var responseStatus: HttpResponseStatus = HttpResponseStatus.OK
    private val responseHeaders = DefaultHttpHeaders()

    override fun setStatus(statusCode: HttpStatusCode) {
        val statusCodeInt = statusCode.value
        val cached = if (statusCodeInt in 1..responseStatusCache.lastIndex) responseStatusCache[statusCodeInt] else null

        responseStatus = cached?.takeIf { cached.reasonPhrase() == statusCode.description }
            ?: HttpResponseStatus(statusCode.value, statusCode.description)
    }

    override val headers: ResponseHeaders = object : ResponseHeaders() {
        override fun engineAppendHeader(name: String, value: String) {
            if (responseMessageSent) {
                if (responseMessage.isCancelled) throw CancellationException("Call execution has been cancelled")
                throw UnsupportedOperationException(
                    "Headers can no longer be set because response was already completed"
                )
            }
            responseHeaders.add(name, value)
        }

        override fun get(name: String): String? = responseHeaders.get(name)
        override fun getEngineHeaderNames(): List<String> = responseHeaders.map { it.key }
        override fun getEngineHeaderValues(name: String): List<String> = responseHeaders.getAll(name) ?: emptyList()
    }

    override fun responseMessage(chunked: Boolean, last: Boolean): Any {
        val responseMessage = DefaultHttpResponse(protocol, responseStatus, responseHeaders)
        if (chunked) {
            setChunked(responseMessage)
        }
        return responseMessage
    }

    override fun responseMessage(chunked: Boolean, data: ByteArray): Any {
        val responseMessage = DefaultFullHttpResponse(
            protocol,
            responseStatus,
            Unpooled.wrappedBuffer(data),
            responseHeaders,
            EmptyHttpHeaders.INSTANCE
        )
        if (chunked) {
            setChunked(responseMessage)
        }
        return responseMessage
    }

    override suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade) {
        TODO()
    }

    private fun setChunked(message: HttpResponse) {
        if (message.status().code() != HttpStatusCode.SwitchingProtocols.value) {
            HttpUtil.setTransferEncodingChunked(message, true)
        }
    }
}
