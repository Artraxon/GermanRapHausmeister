package de.rtrx.a.derap

import com.google.inject.Guice
import de.rtrx.a.*
import de.rtrx.a.database.DDL
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger {  }
fun main(args: Array<String>) {
    val options = parseOptions(args)
    val configPath = options.get("configPath") as String? ?: ""
    val useDB = options.get("useDB") as Boolean? ?: true
    val restart = options.get("restart") as Boolean? ?: true

    val injector = Guice.createInjector(CoreModule(initConfig(configPath, RedditSpec, DBSpec, DerapConfig), useDB),
            DerapFlowModule(restart))
    injector.getInstance(DDL::class.java).init(
            createDDL = (options.get("createDDL") as Boolean?) ?: true,
            createFunctions = (options.get("createDBFunctions") as Boolean?) ?: true
    )
    if(!((options.get("startDispatcher") as Boolean?) ?:true)) {
        logger.info { "Exiting before starting dispatcher" }
        System.exit(2)
    }

    val dispatcher: DerapFlowDispatcher = injector.getInstance(DerapFlowDispatcher::class.java)

    Runtime.getRuntime().addShutdownHook(thread(false) {
        runBlocking { dispatcher.stop() }
        logger.info { "Stopping Bot" }
    })

    runBlocking {
        dispatcher.join()
    }
}

