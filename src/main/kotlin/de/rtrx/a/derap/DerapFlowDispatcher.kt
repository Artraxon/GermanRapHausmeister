package de.rtrx.a.derap

import de.rtrx.a.derap.DerapFlow
import de.rtrx.a.flow.ArchivingFlow
import de.rtrx.a.flow.IFlowDispatcherStub
import de.rtrx.a.flow.IsolationStrategy
import de.rtrx.a.flow.MarkAsReadFlow
import de.rtrx.a.flow.events.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import net.dean.jraw.models.Message
import javax.inject.Inject
import javax.inject.Named


class DerapFlowDispatcher @Inject constructor(
        private val stub: IFlowDispatcherStub<DerapFlow, DerapFlowFactory>,
        incomingMessageMultiplexerBuilder: @JvmSuppressWildcards EventMultiplexerBuilder<Message, @JvmSuppressWildcards EventMultiplexer<Message>, @JvmSuppressWildcards ReceiveChannel<Message>>,
        sentMessageMultiplexerBuilder: @JvmSuppressWildcards EventMultiplexerBuilder<Message, @JvmSuppressWildcards EventMultiplexer<Message>, @JvmSuppressWildcards ReceiveChannel<Message>>,
        incomingMessageFactory: IncomingMessageFactory,
        sentMessageFactory: SentMessageFactory,
        isolationStrategy: IsolationStrategy,
        markAsReadFlow: MarkAsReadFlow,
        archivingFlow: ArchivingFlow,
        @Named("restart") restart: Boolean,
) : IFlowDispatcherStub<DerapFlow, DerapFlowFactory> by stub{

    private val incomingMessageMultiplexer: EventMultiplexer<Message>
    private val sentMessageMultiplexer: EventMultiplexer<Message>

    private val incomingMessagesEvent: IncomingMessagesEvent
    private val sentMessageEvent: SentMessageEvent


    init {
        val (incomingEvent, incomingChannel) = incomingMessageFactory.create("")
        val (sentEvent, sentChannel) = sentMessageFactory.create("")
        this.incomingMessagesEvent = incomingEvent
        this.sentMessageEvent = sentEvent

        stub.flowFactory.setIncomingMessages(incomingMessagesEvent)
        stub.flowFactory.setSentMessages(sentMessageEvent)

        incomingMessageMultiplexer = incomingMessageMultiplexerBuilder
                .setOrigin(incomingChannel)
                .setIsolationStrategy(isolationStrategy)
                .build()
        sentMessageMultiplexer = sentMessageMultiplexerBuilder
                .setOrigin(sentChannel)
                .setIsolationStrategy(isolationStrategy)
                .build()

        runBlocking {
            stub.registerMultiplexer(incomingMessagesEvent, incomingMessageMultiplexer)
            stub.registerMultiplexer(sentMessageEvent, sentMessageMultiplexer)
        }
        incomingMessageMultiplexer.addListener(markAsReadFlow, markAsReadFlow::markAsRead)
        launcherScope.launch { archivingFlow.start() }
        sentMessageMultiplexer.addListener(archivingFlow, archivingFlow::saveMessage)

        if (restart) {
            launcherScope.launch {
                archivingFlow.finished.await()
                sentMessageMultiplexer.removeListeners(archivingFlow)
                stub.setupAndStartFlows(DerapFlow::relaunch) { provider -> stub.flowFactory.recreateFlows(stub, provider, archivingFlow.messages) }.joinAll()
            }
        }
        stub.start()

        this.incomingMessagesEvent.start()
        this.sentMessageEvent.start()

        KotlinLogging.logger { }.info("Started UnexFlow Dispatcher")
    }

}

