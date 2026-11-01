package elearning

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Parameter
import io.r2dbc.spi.Statement
import org.aopalliance.intercept.MethodInterceptor
import org.reactivestreams.Publisher
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.util.ClassUtils
import reactor.core.publisher.Mono
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.lang.reflect.Array as ReflectArray

// cuenta las sentencias SQL que la aplicación manda a PostgreSQL, para medir el coste de una petición (revisión de las
// Fases 2 y 3: M1 de #104, M4 de #183). envuelve el ConnectionFactory y cuenta cada createStatement de sus conexiones.
// guarda además el arreglo ligado más largo (ANY(?)): un alcance que liga listas de ids crece con el catálogo
@TestConfiguration(proxyBeanMethods = false)
class StatementCount {
    companion object {
        val statements = AtomicInteger()
        val largestArray = AtomicInteger()

        fun reset() {
            statements.set(0)
            largestArray.set(0)
        }

        @JvmStatic
        @Bean
        fun countingConnectionFactory(): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any = if (bean is ConnectionFactory) counting(bean) else bean
            }

        private fun counting(factory: ConnectionFactory): Any =
            ProxyFactory(factory)
                .apply {
                    isProxyTargetClass = true
                    addAdvice(
                        MethodInterceptor { call ->
                            val result = call.proceed()
                            @Suppress("UNCHECKED_CAST")
                            if (call.method.name == "create") Mono.from(result as Publisher<Connection>).map { counting(it) } else result
                        },
                    )
                }.proxy

        private fun counting(connection: Connection): Connection =
            Proxy.newProxyInstance(connection.javaClass.classLoader, ClassUtils.getAllInterfacesForClass(connection.javaClass)) { _, method, args ->
                if (method.name == "createStatement") statements.incrementAndGet()
                val result = invoke(connection, method, args)
                if (result is Statement) measuring(result) else result
            } as Connection

        // mide los arreglos que se ligan a la sentencia; las llamadas encadenadas (bind devuelve la sentencia) siguen pasando
        private fun measuring(statement: Statement): Statement {
            lateinit var proxy: Statement
            proxy =
                Proxy.newProxyInstance(statement.javaClass.classLoader, ClassUtils.getAllInterfacesForClass(statement.javaClass)) { _, method, args ->
                    if (method.name == "bind" && args?.size == 2) {
                        val value = args[1].let { if (it is Parameter) it.value else it }
                        if (value != null && value.javaClass.isArray) {
                            val length = ReflectArray.getLength(value)
                            largestArray.accumulateAndGet(length) { a, b -> maxOf(a, b) }
                        }
                    }
                    val result = invoke(statement, method, args)
                    if (result === statement) proxy else result
                } as Statement
            return proxy
        }

        private fun invoke(
            target: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? =
            try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
    }
}
