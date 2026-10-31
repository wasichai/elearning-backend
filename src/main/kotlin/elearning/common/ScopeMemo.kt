package elearning.common

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import wasichai.core.data.RecordCriterion
import wasichai.core.identity.AuthenticatedUser
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

// el alcance de lectura de una persona sobre un objeto, calculado una vez por petición (M1 de #104). cada cálculo lista
// matrículas, unidades o lecciones, y cada lectura con alcance lo pedía otra vez: el aula repetía la cadena entera por
// cada recurso. dentro de una petición lo que se ve no cambia: lo que la petición escribe no amplía su propio alcance.
// fuera de una petición (tareas en segundo plano) no hay memoria y se calcula siempre
class ScopeMemo {
    private val criteria = ConcurrentHashMap<String, Optional<RecordCriterion>>()

    companion object {
        val KEY: Class<ScopeMemo> = ScopeMemo::class.java

        suspend fun of(
            scope: String,
            caller: AuthenticatedUser,
            objectName: String,
            compute: suspend () -> RecordCriterion?,
        ): RecordCriterion? {
            val memo = currentCoroutineContext()[ReactorContext]?.context?.getOrEmpty<ScopeMemo>(KEY)?.orElse(null) ?: return compute()
            val key = "$scope:${caller.organizationId}:${caller.userId}:$objectName"
            memo.criteria[key]?.let { return it.orElse(null) }
            val criterion = compute()
            memo.criteria.putIfAbsent(key, Optional.ofNullable(criterion))
            return criterion
        }
    }
}

// una memoria nueva por petición, en el contexto de Reactor que heredan sus corrutinas
@Component
class ScopeMemoFilter : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> = chain.filter(exchange).contextWrite { it.put(ScopeMemo.KEY, ScopeMemo()) }
}
