package de.rtrx.a.tihi

import com.uchuhimo.konf.Config
import de.rtrx.a.RedditSpec
import de.rtrx.a.database.ConversationLinkage
import de.rtrx.a.database.Linkage
import de.rtrx.a.database.ObservationLinkage
import de.rtrx.a.flow.*
import de.rtrx.a.flow.events.EventMultiplexerBuilder
import de.rtrx.a.flow.events.EventType
import de.rtrx.a.flow.events.IncomingMessagesEvent
import de.rtrx.a.flow.events.SentMessageEvent
import de.rtrx.a.flow.events.comments.FullComments
import de.rtrx.a.flow.events.comments.ManuallyFetchedEvent
import de.rtrx.a.monitor.IDBCheckBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import mu.KotlinLogging
import net.dean.jraw.RedditClient
import net.dean.jraw.models.Comment
import net.dean.jraw.models.DistinguishedStatus
import net.dean.jraw.models.Submission
import net.dean.jraw.references.CommentReference
import net.dean.jraw.references.PublicContributionReference
import net.dean.jraw.references.SubmissionReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

class TihiFlow (
        private val flowStub: FlowStub<SubmissionReference, TihiFlow>,
        private val callback: Callback<in FlowResult<TihiFlow>, Unit>,
        private val composingFn: MessageComposer,
        private val replyFn: Replyer,
        private val shameReply: Replyer,
        private val sentMessages: SentMessageEvent,
        private val incomingMessages: IncomingMessagesEvent,
        private val manuallyFetchedEvent: ManuallyFetchedEvent,
        private val observationLinkage: ObservationLinkage,
        private val conversationLinkage: ConversationLinkage,
        private val dbCheckBuilder: IDBCheckBuilder,
        private val commentsHookedMonitorBuilder: CommentsHookedMonitorBuilder,
        private val conversation: Conversation,
        private val delayedDeleteFactory: DelayedDeleteFactory,
        private val config: Config
): IFlowStub<SubmissionReference, TihiFlow> by flowStub, Flow{
    private val logger = KotlinLogging.logger {  }

    override suspend fun start() {
        launch {
            try {
                logger.trace("Starting flow for ${initValue.fullName}")
                if (observationLinkage.insertSubmission(initValue.inspect()) == 0) {
                    logger.trace("Cancelling flow for ${initValue.fullName} because the submission is already present")
                    callback(SubmissionAlreadyPresent(this@TihiFlow))
                    return@launch
                }
                val awaitedReply = async { conversation.run { waitForCompletion(produceCheckMessage(initValue.id)) } }
                val conversationSubscriptions = listOf(
                        Subscription.create(conversation::start, sentMessages),
                        Subscription.create(conversation::reply, incomingMessages))

                var comment: Comment? = null
                var ref: CommentReference? = null

                withSubscriptions(conversationSubscriptions){
                    composingFn(initValue.inspect().author, initValue.inspect().permalink)
                    val deletion = delayedDeleteFactory.create(initValue, this, 0)
                    deletion.start()
                    val answered = deletion.safeSelectTo(awaitedReply.onAwait)

                    if (!answered.bool) {
                        callback(NoAnswerReceived(this@TihiFlow))
                        return@withSubscriptions
                    }
                    val (commentValue, commentRef) =
                            if (answered is ApprovalAndScoreCheckFactory.NotDeletedSufficientScore) {
                                initValue.flair(config[RedditSpec.subreddit]).updateToCssClass("shame", "SHAME")
                                shameReply(initValue.inspect(), "")
                            } else {
                                replyFn(initValue.inspect(), awaitedReply.getCompleted().body)
                                        .also { (comment, _) ->
                                            conversationLinkage.saveCommentMessage(initValue.id, awaitedReply.await(), comment)
                                        }
                            }
                    comment = commentValue
                    ref = commentRef
                }

                if(ref == null || comment == null) return@launch
                ref!!.distinguish(DistinguishedStatus.MODERATOR, true)

                logger.trace("Starting the monitors for ${initValue.fullName}")
                val dbCheckMonitor = dbCheckBuilder.setCommentEvent(manuallyFetchedEvent)
                        .setBotComment(comment)
                        .build(initValue)

                val hookedMonitor = commentsHookedMonitorBuilder
                        .setBotComment(comment)
                        .build(initValue)

                val subscriptions = listOf(
                        Subscription.create(dbCheckMonitor::saveToDB, manuallyFetchedEvent),
                        Subscription.create(hookedMonitor::acceptData, manuallyFetchedEvent))
                withSubscriptions(subscriptions) {
                    hookedMonitor.start()
                    dbCheckMonitor.start()
                }

                callback(FlowResult.NotFailedEnd.RegularEnd(this@TihiFlow))

            } catch (c: CancellationException){
                callback(FlowResult.FailedEnd.Cancelled(this@TihiFlow))
                logger.warn("Flow for submission ${initValue.fullName} was cancelled")
            }
        }
    }
}

class ApprovalAndScoreCheckFactory @Inject constructor(
        private val linkage: ObservationLinkage,
        private val config: Config
): Provider<DeletePrevention> {
    override fun get(): DeletePrevention {
        return object : DeletePrevention {
            override suspend fun check(publicRef: PublicContributionReference): DelayedDelete.DeleteResult {
                return linkage.createCheckSelectValues(
                        publicRef.fullName,
                        null,
                        null,
                        emptyArray(),
                        {
                            if (it.get("approved")?.asBoolean ?: false) {
                                DelayedDelete.Companion.NotDeletedApproved()
                            } else {
                                if (it.get("score")?.asInt ?: 0 >= config[TihiConfig.Approval.minScore]) NotDeletedSufficientScore()
                                else DelayedDelete.DeleteResult.WasDeleted()
                            }
                        }
                ).checkResult as DelayedDelete.DeleteResult
            }
        }
    }
    class NotDeletedSufficientScore : DelayedDelete.DeleteResult.NotDeleted()
}
interface TihiFlowFactory : FlowFactory<TihiFlow, SubmissionReference>{
    fun setSentMessages(sentMessages: SentMessageEvent)
    fun setIncomingMessages(incomingMessages: IncomingMessagesEvent)
}


class RedditTihiFlowFactory @Inject constructor(
        private val config: Config,
        private val composingFn: MessageComposer,
        private val replyFn: Replyer,
        @param:Named("shameReply") private val shameReply: Replyer,
        private val dbCheckFactory: Provider<IDBCheckBuilder>,
        private val botCommentMonitorFactory: Provider<CommentsHookedMonitorBuilder>,
        private val multiplexerProvider: Provider<EventMultiplexerBuilder<FullComments, *, ReceiveChannel<FullComments>>>,
        private val conversationFactory: Provider<Conversation>,
        private val delayedDeleteFactory: DelayedDeleteFactory,
        private val observationLinkage: ObservationLinkage,
        private val conversationLinkage: ConversationLinkage
) : TihiFlowFactory {
    private lateinit var sentMessages: SentMessageEvent
    private lateinit var incomingMessages: IncomingMessagesEvent
    private val unregisterScope = CoroutineScope(Dispatchers.Default)

    override suspend fun create(dispatcher: FlowDispatcherInterface<TihiFlow>, initValue: SubmissionReference, callback: Callback<in FlowResult<TihiFlow>, Unit>): TihiFlow {
        val stub = FlowStub(
                initValue,
                { TihiFlow: TihiFlow, fn: suspend (Any) -> Unit, type: EventType<Any> ->
                    dispatcher.subscribe(TihiFlow, fn, type)
                },
                dispatcher::unsubscribe,
                CoroutineScope(Dispatchers.Default)
        )
        callback.addAction { unregisterScope.launch { dispatcher.unregisterEvent(ManuallyFetchedEvent::class, initValue) }}
        val flow = TihiFlow(
                stub,
                callback,
                composingFn,
                replyFn,
                shameReply,
                sentMessages,
                incomingMessages,
                dispatcher.createNewEvent(ManuallyFetchedEvent::class, initValue, multiplexerProvider.get()),
                observationLinkage,
                conversationLinkage,
                dbCheckFactory.get(),
                botCommentMonitorFactory.get(),
                conversationFactory.get(),
                delayedDeleteFactory,
                config
        )
        stub.setOuter(flow)
        return flow
    }


    override fun setSentMessages(sentMessages: SentMessageEvent) {
        if (!this::sentMessages.isInitialized) this.sentMessages = sentMessages
    }

    override fun setIncomingMessages(incomingMessages: IncomingMessagesEvent) {
        if (!this::incomingMessages.isInitialized) this.incomingMessages = incomingMessages
    }
}

class ShameReply @Inject constructor(
        private val redditClient: RedditClient,
        private val config: Config): Replyer {
    override fun invoke(submission: Submission, reason: String): Pair<Comment, CommentReference> {
        val comment = submission.toReference(redditClient)
                .reply(config[TihiConfig.Approval.shameComment])
        return comment to comment.toReference(redditClient)
    }
}
