package com.pvd.scwcontrolroom.kit

/** Answers requests from canned JSON by path, and records what was asked. */
class FakeTransport : HttpTransport {
    val responses = mutableMapOf<String, Pair<Int, String>>()
    val requests = mutableListOf<HttpRequest>()

    override suspend fun send(request: HttpRequest): HttpResponse = synchronized(this) {
        requests += request
        val (status, body) = responses[request.path] ?: (404 to """{"message":"not found"}""")
        HttpResponse(status, body)
    }

    @Synchronized
    fun queries(path: String): List<Map<String, String>> = requests.filter { it.path == path }.map { it.query }
}
