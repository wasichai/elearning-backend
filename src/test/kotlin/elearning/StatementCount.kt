package elearning

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import org.aopalliance.intercept.MethodInterceptor
import org.reactivestreams.Publisher
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.util.ClassUtils
import reactor.core.publisher.Mono
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

// cuenta las sentencias SQL que la aplicación manda a PostgreSQL, para medir el coste de una petición (revisión de las
// Fases 2 y 3: M1 de #104, M4 de #183). envuelve el ConnectionFactory y cuenta cada createStatement de sus conexiones
@TestConfiguration(proxyBeanMethods = false)
class StatementCount {
    companion object {
        val statements = AtomicInteger()

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
                try {
                    method.invoke(connection, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as Connection
    }
}
