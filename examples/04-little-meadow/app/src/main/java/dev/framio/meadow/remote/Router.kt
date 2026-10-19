package dev.framio.meadow.remote

/**
 * Method + path patterns like "/api/family/{id}" → handlers. Everything needs a signed-in session
 * unless the route is marked public (the login page and what it shows). Pure, unit-tested.
 */
class Router {

    fun interface Handler {
        fun handle(req: Request, params: Map<String, String>): Response
    }

    private class Route(val method: String, val parts: List<String>, val public: Boolean, val handler: Handler)

    sealed interface Match {
        data class Found(val handler: Handler, val params: Map<String, String>, val public: Boolean) : Match
        /** The path exists, but not for this method. */
        data class WrongMethod(val allowed: List<String>) : Match
        data object NotFound : Match
    }

    private val routes = ArrayList<Route>()

    fun get(pattern: String, public: Boolean = false, h: Handler) = add("GET", pattern, public, h)
    fun post(pattern: String, public: Boolean = false, h: Handler) = add("POST", pattern, public, h)
    fun put(pattern: String, public: Boolean = false, h: Handler) = add("PUT", pattern, public, h)
    fun delete(pattern: String, public: Boolean = false, h: Handler) = add("DELETE", pattern, public, h)

    fun add(method: String, pattern: String, public: Boolean, h: Handler) {
        routes += Route(method, split(pattern), public, h)
    }

    /** HEAD is answered like GET (without a body). */
    fun match(method: String, path: String): Match {
        val parts = split(path)
        val m = if (method == "HEAD") "GET" else method
        val allowed = ArrayList<String>()
        for (r in routes) {
            val params = bind(r.parts, parts) ?: continue
            if (r.method == m) return Match.Found(r.handler, params, r.public)
            allowed += r.method
        }
        return if (allowed.isEmpty()) Match.NotFound else Match.WrongMethod(allowed.distinct())
    }

    /**
     * Routes [req], answering 401 for a private route without a session, 404/405 for unknown ones,
     * and turning [HttpException]s into friendly JSON errors (anything else is a 500).
     */
    fun serve(req: Request, signedIn: (Request) -> Boolean, onError: (Exception) -> Unit = {}): Response = try {
        when (val m = match(req.method, req.path)) {
            Match.NotFound -> Response.error(404, "Not found")
            is Match.WrongMethod -> Response.error(405, "Method not allowed").header("Allow", m.allowed.joinToString(", "))
            is Match.Found ->
                if (!m.public && !signedIn(req)) Response.error(401, "Please sign in with the PIN")
                else m.handler.handle(req, m.params)
        }
    } catch (e: HttpException) {
        Response.error(e.status, e.message ?: "Error")
    } catch (e: Exception) {
        onError(e)
        Response.error(500, "Something went wrong on the frame")
    }

    private fun bind(pattern: List<String>, path: List<String>): Map<String, String>? {
        if (pattern.size != path.size) return null
        var params: HashMap<String, String>? = null
        for (i in pattern.indices) {
            val p = pattern[i]
            if (p.length > 2 && p.startsWith('{') && p.endsWith('}')) {
                if (path[i].isEmpty()) return null
                (params ?: HashMap<String, String>().also { params = it })[p.substring(1, p.length - 1)] = path[i]
            } else if (p != path[i]) {
                return null
            }
        }
        return params ?: emptyMap()
    }

    private fun split(path: String): List<String> = path.trim('/').let { if (it.isEmpty()) emptyList() else it.split('/') }
}
