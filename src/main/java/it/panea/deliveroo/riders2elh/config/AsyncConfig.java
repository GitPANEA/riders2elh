package it.panea.deliveroo.riders2elh.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Pool dedicato all'elaborazione asincrona dei batch, deliberatamente piccolo: ogni
 * record del batch apre/chiude la propria connessione Hikari (sostituisciVersioneCorrente
 * è @Transactional per singolo record), quindi il rischio non è la durata di una singola
 * elaborazione ma troppi batch in parallelo che esauriscono il pool Hikari (20 connessioni,
 * condiviso con il traffico REST sincrono). Il caso d'uso attuale è un batch alla volta o
 * pochi in parallelo, non l'elaborazione massiva concorrente.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("batchTaskExecutor")
    public TaskExecutor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("batch-async-");
        executor.initialize();
        return executor;
    }
}
