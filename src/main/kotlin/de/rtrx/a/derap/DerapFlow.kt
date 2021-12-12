package de.rtrx.a.derap

import com.uchuhimo.konf.Config
import de.rtrx.a.RedditSpec
import de.rtrx.a.database.ConversationLinkage
import de.rtrx.a.database.Linkage
import de.rtrx.a.database.ObservationLinkage
import de.rtrx.a.derap.DerapConfig
import de.rtrx.a.flow.*
import de.rtrx.a.flow.events.*
import de.rtrx.a.flow.events.comments.FullComments
import de.rtrx.a.flow.events.comments.ManuallyFetchedEvent
import de.rtrx.a.getCompletedOrNull
import de.rtrx.a.getSubmissionJson
import de.rtrx.a.jrawExtension.UpdatedCommentNode
import de.rtrx.a.monitor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import mu.KotlinLogging
import net.dean.jraw.RedditClient
import net.dean.jraw.models.Comment
import net.dean.jraw.models.DistinguishedStatus
import net.dean.jraw.models.Message
import net.dean.jraw.references.CommentReference
import net.dean.jraw.references.SubmissionReference
import java.util.*
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
private val logger = KotlinLogging.logger { }
/**
 * @param composingFn Function that sends the message to the user. First Argument is the recipient, second one the url to the post
 */
class DerapFlow(
        private val flowStub: IFlowStub<SubmissionReference, DerapFlow>,
        private val callback: Callback<in FlowResult<DerapFlow>, Unit>,
        private val composingFn: MessageComposer,
        private val replyFn: Replyer,
        private val sentMessages: SentMessageEvent,
        private val incomingMessages: IncomingMessagesEvent,
        private val commentsFetchedEvent: ManuallyFetchedEvent,
        private val conversationLinkage: ConversationLinkage,
        private val observationLinkage: ObservationLinkage,
        private val monitorBuilder: IDBCheckBuilder,
        private val conversation: JumpstartConversation<String>,
        private val delayedDeleteFactory: DelayedDeleteFactory,
        private val flairCheck: () -> Boolean
) : IFlowStub<SubmissionReference, DerapFlow> by flowStub,
        RelaunchableFlow{

    private val started: CompletableDeferred<Unit> = CompletableDeferred()
    private var ownMessageID: String? = null
    private val defferedComment: CompletableDeferred<Comment> = CompletableDeferred()
    private val defferedCommentRef: CompletableDeferred<CommentReference> = CompletableDeferred()
    private var foundComment: Comment? = null
    val incompletableDefferedComment: Deferred<Comment> get() = defferedComment
    val comment: Comment?
        get() {
            if (foundComment != null) return foundComment
            else return defferedComment.getCompletedOrNull()
        }

    lateinit var monitor: IDBCheck

    /**
     * Recreate Flow which was cancelled when already at monitoring stage
     */
    constructor(
            flowStub: IFlowStub<SubmissionReference, DerapFlow>,
            callback: Callback<in FlowResult<DerapFlow>, Unit>,
            composingFn: MessageComposer,
            replyFn: Replyer,
            sentMessages: SentMessageEvent,
            incomingMessages: IncomingMessagesEvent,
            commentsFetchedEvent: ManuallyFetchedEvent,
            conversationLinkage: ConversationLinkage,
            observationLinkage: ObservationLinkage,
            monitorBuilder: IDBCheckBuilder,
            conversation: JumpstartConversation<String>,
            delayedDeleteFactory: DelayedDeleteFactory,
            commentRef: CommentReference,
            comment: Comment?,
            flairCheck: () -> Boolean
    ): this(
            flowStub,
            callback,
            composingFn,
            replyFn,
            sentMessages,
            incomingMessages,
            commentsFetchedEvent,
            conversationLinkage,
            observationLinkage,
            monitorBuilder,
            conversation,
            delayedDeleteFactory,
            flairCheck
    ){
        defferedCommentRef.complete(commentRef)
        this.foundComment = comment
    }

    /**
     *
     */
    constructor(
            flowStub: IFlowStub<SubmissionReference, DerapFlow>,
            callback: Callback<in FlowResult<DerapFlow>, Unit>,
            composingFn: MessageComposer,
            replyFn: Replyer,
            sentMessages: SentMessageEvent,
            incomingMessages: IncomingMessagesEvent,
            commentsFetchedEvent: ManuallyFetchedEvent,
            conversationLinkage: ConversationLinkage,
            observationLinkage: ObservationLinkage,
            monitorBuilder: IDBCheckBuilder,
            conversation: JumpstartConversation<String>,
            delayedDeleteFactory: DelayedDeleteFactory,
            ownMessageID: String,
            flairCheck: () -> Boolean
    ): this(
            flowStub,
            callback,
            composingFn,
            replyFn,
            sentMessages,
            incomingMessages,
            commentsFetchedEvent,
            conversationLinkage,
            observationLinkage,
            monitorBuilder,
            conversation,
            delayedDeleteFactory,
            flairCheck
    ){
        this.ownMessageID = ownMessageID
    }

    suspend fun checkSubmission(): Boolean {
        logger.trace("Starting flow for ${initValue.fullName}")
        if (observationLinkage.insertSubmission(initValue.inspect()) == 0) {
            logger.trace("Cancelling flow for ${initValue.fullName} because the submission is already present")
            callback(SubmissionAlreadyPresent(this))
            return false
        } else return true
    }

    suspend fun startConversation(): Deferred<Message> {
        val awaitedReply = async { conversation.run { waitForCompletion(produceCheckMessage(initValue.id)) } }

        return awaitedReply
    }

    suspend fun waitForAnswer(awaitedReply: Deferred<Message>, skip: Long, ownMessageID: String? = null): Pair<Message?, DelayedDelete.DeleteResult> {
        val subscriptions = if (ownMessageID == null) listOf( Subscription.create(conversation::start, sentMessages), Subscription.create(conversation::reply, incomingMessages) )
                                 else listOf(Subscription.create(conversation::reply, incomingMessages))

        var result: Pair<Message?, DelayedDelete.DeleteResult>? = null
        withSubscriptions(subscriptions) {
            if (ownMessageID == null) composingFn(initValue.inspect().author, initValue.inspect().permalink)
            else conversation.jumpstart(ownMessageID)

            val deletion = delayedDeleteFactory.create(initValue, this, skip)
            deletion.start()
            val answered = deletion.safeSelectTo(awaitedReply.onAwait)

            if (!answered.bool) {
                callback(NoAnswerReceived(this@DerapFlow))
                logger.info { "No answer received for flow for ${initValue.fullName}" }
                result = null to answered
            }
            result = awaitedReply.getCompletedOrNull() to answered
        }
        return result!!

    }

    suspend fun replyComment(reply: Message){
        val (comment, ref) = replyFn(initValue.inspect(), reply.body)
        defferedComment.complete(comment)
        defferedCommentRef.complete(ref)
        ref.distinguish(DistinguishedStatus.MODERATOR, true)
        conversationLinkage.saveCommentMessage(initValue.id, reply, comment)
    }

    suspend fun monitor(){
        monitor = monitorBuilder.setCommentEvent(commentsFetchedEvent).setBotComment(comment).build(initValue)
        logger.trace("Starting Monitor for ${initValue.fullName}")
        withSubscription(Subscription.create(monitor::saveToDB, commentsFetchedEvent)) {
            monitor.start()
        }
    }

    override suspend fun relaunch() {
        val skip = System.currentTimeMillis() - initValue.inspect().created.time
        try {
            if (!started.complete(Unit) || checkSubmission()) {
                callback(FlowResult.FailedEnd.LogicFailed(this))
                return
            }
            if(flairCheck()){
                if (!defferedCommentRef.isCompleted){
                    val awaitedReply = startConversation()
                    val awaited = waitForAnswer(awaitedReply, skip, ownMessageID)
                    if(awaited.first != null) replyComment(awaited.first!!)
                    else if (!awaited.second.bool) return
                }
            }
            monitor()
            callback(FlowResult.NotFailedEnd.RegularEnd(this@DerapFlow))
        } catch (c: CancellationException){
            callback(FlowResult.FailedEnd.Cancelled(this@DerapFlow))
            logger.warn("Flow for submission ${initValue.fullName} was cancelled")
        }
    }

    override suspend fun start() {
        try {
            if (!started.complete(Unit)) {
                callback(FlowResult.FailedEnd.LogicFailed(this))
                return
            }
            if(!checkSubmission()) return
            if(flairCheck()){
                val awaitedReply = startConversation()
                val awaited = waitForAnswer(awaitedReply, 0)
                if(awaited.first != null) replyComment(awaited.first!!)
                else if (!awaited.second.bool) return
            }
            monitor()
            callback(FlowResult.NotFailedEnd.RegularEnd(this@DerapFlow))
        } catch (c: CancellationException){
            callback(FlowResult.FailedEnd.Cancelled(this@DerapFlow))
            logger.warn("Flow for submission ${initValue.fullName} was cancelled")
        }
    }

    fun addCallback(action: (FlowResult<DerapFlow>) -> Unit){
        callback.addAction { action(it as FlowResult<DerapFlow>) }
    }

    companion object{
        val logger = KotlinLogging.logger {  }
    }
}


interface DerapFlowFactory : RelaunchableFlowFactory<DerapFlow, SubmissionReference, Collection<Message>>{
    fun setSentMessages(sentMessages: SentMessageEvent)
    fun setIncomingMessages(incomingMessages: IncomingMessagesEvent)
}

class RedditDerapFlowFactory @Inject constructor(
        @param:Named("delayToDeleteMillis") private val delayToDeleteMillis: Long,
        @param:Named("delayToFinishMillis") private val delayToFinishMillis: Long,
        private val composingFn: MessageComposer,
        private val replyFn: Replyer,
        private val monitorFactory: Provider<IDBCheckBuilder>,
        private val conversationLinkage: ConversationLinkage,
        private val observationLinkage: ObservationLinkage,
        private val linkage: Linkage,
        private val conversationFactory: Provider<JumpstartConversation<String>>,
        private val delayedDeleteFactory: DelayedDeleteFactory,
        private val multiplexerProvider: Provider<EventMultiplexerBuilder<FullComments, *, ReceiveChannel<FullComments>>>,
        private val config: Config,
        private val redditClient: RedditClient,
) : DerapFlowFactory {
    private lateinit var _sentMessages: SentMessageEvent
    private lateinit var _incomingMessages: IncomingMessagesEvent
    private val unregisterScope = CoroutineScope(Dispatchers.Default)

    private fun provideFlairCheck(initValue: SubmissionReference): ()-> Boolean{
        val flairCheck: () -> Boolean = {
            val flairObject = redditClient.getSubmissionJson(initValue.fullName)["link_flair_css_class"]
            if(flairObject.isJsonNull) false
            else flairObject.asString == config[DerapConfig.linkFlairClass]
        }
        return flairCheck;
    }
    private suspend fun provideStub(
            dispatcher: FlowDispatcherInterface<DerapFlow>,
            initValue: SubmissionReference,
            callback: Callback<in FlowResult<DerapFlow>, Unit>
    ): FlowStub<SubmissionReference, DerapFlow> {
        val stub = FlowStub(
                initValue,
                { derapFlow: DerapFlow, fn: suspend (Any) -> Unit, type: EventType<Any> ->
                    dispatcher.subscribe(derapFlow, fn, type)
                },
                dispatcher::unsubscribe,
                CoroutineScope(Dispatchers.Default)
        )
        callback.addAction { unregisterScope.launch { dispatcher.unregisterEvent(ManuallyFetchedEvent::class, initValue) }}
        return stub
    }
    override suspend fun create(
            dispatcher: FlowDispatcherInterface<DerapFlow>,
            initValue: SubmissionReference,
            callback: Callback<in FlowResult<DerapFlow>, Unit>
    ): DerapFlow {
        val stub = provideStub(dispatcher, initValue, callback)
        val flow = DerapFlow(
                stub,
                callback,
                composingFn,
                replyFn,
                _sentMessages,
                _incomingMessages,
                dispatcher.createNewEvent(ManuallyFetchedEvent::class, initValue ,multiplexerProvider.get())  ,
                conversationLinkage,
                observationLinkage,
                monitorFactory.get(),
                conversationFactory.get(),
                delayedDeleteFactory,
                provideFlairCheck(initValue)
        )
        stub.setOuter(flow)
        return flow
    }

    override fun setSentMessages(sentMessages: SentMessageEvent) {
        if (!this::_sentMessages.isInitialized) this._sentMessages = sentMessages
    }

    override fun setIncomingMessages(incomingMessages: IncomingMessagesEvent) {
        if (!this::_incomingMessages.isInitialized) this._incomingMessages = incomingMessages
    }

    override suspend fun recreateFlows(dispatcher: FlowDispatcherInterface<DerapFlow>, callbackProvider: Provider<Callback<in FlowResult<DerapFlow>, Unit>>, additionalData: Collection<Message>): Collection<DerapFlow> {
        val flows = mutableListOf<DerapFlow>()

        val unansweredPst = linkage.connection.prepareStatement(
                """SELECT submissions.id, title, submissions.author_id, created FROM submissions LEFT JOIN relevant_messages rm on submissions.id = rm.submission_id
                        WHERE rm.id IS NULL AND submissions.created >= now() - ? * INTERVAL '1 MILLISECONDS'"""
        )

        unansweredPst.setLong(1, delayToDeleteMillis)
        unansweredPst.execute()
        val unansweredResultSet = unansweredPst.resultSet
        while(unansweredResultSet.next()){
            val id = unansweredResultSet.getString(1)
            val title = unansweredResultSet.getString(2)
            val author = unansweredResultSet.getString(3)
            val created = unansweredResultSet.getTimestamp(4)

            val callback = callbackProvider.get()
            val initValue = redditClient.submission(id)
            val stub = provideStub(dispatcher, initValue , callback)

            val ownMessage = additionalData.find { produceCheckMessage(id)(it) }


            val flow = if (ownMessage == null) {
                DerapFlow(
                        stub,
                        callback,
                        composingFn,
                        replyFn,
                        _sentMessages,
                        _incomingMessages,
                        dispatcher.createNewEvent(ManuallyFetchedEvent::class, initValue ,multiplexerProvider.get())  ,
                        conversationLinkage,
                        observationLinkage,
                        monitorFactory.get(),
                        conversationFactory.get(),
                        delayedDeleteFactory,
                        provideFlairCheck(initValue)
                )
            } else {
                DerapFlow(
                        stub,
                        callback,
                        composingFn,
                        replyFn,
                        _sentMessages,
                        _incomingMessages,
                        dispatcher.createNewEvent(ManuallyFetchedEvent::class, initValue, multiplexerProvider.get()),
                        conversationLinkage,
                        observationLinkage,
                        monitorFactory.get(),
                        conversationFactory.get(),
                        delayedDeleteFactory,
                        ownMessage.fullName,
                        provideFlairCheck(initValue)
                )
            }
            stub.setOuter(flow)
            flows.add(flow)

        }
        val pst = linkage.connection.prepareStatement(
                """SELECT submissions.id, title, submissions.author_id, created, cc.comment_id
                    FROM submissions JOIN relevant_messages rm on submissions.id = rm.submission_id JOIN comments_caused cc on rm.id = cc.message_id
                    WHERE submissions.created >= now() - ? * INTERVAL '1 MILLISECONDS'
                    ORDER BY submissions.created""")
        pst.setLong(1, config[RedditSpec.checks.DB.forTimes] * config[RedditSpec.checks.DB.every])
        pst.execute()
        val resultSet = pst.resultSet

        while(resultSet.next()){
            val id = resultSet.getString(1)
            val title = resultSet.getString(2)
            val author = resultSet.getString(3)
            val created = resultSet.getTimestamp(4)
            val commentID = resultSet.getString(5)

            val callback = callbackProvider.get()
            val initValue = redditClient.submission(id)
            val stub = provideStub(dispatcher, initValue , callback)

            //retrieve the comment
            val commentRef = redditClient.comment(commentID)
            val comments = UpdatedCommentNode(initValue.comments()).walkTree().toCollection(LinkedList())
            val comment = comments.find { if(it.subject is Comment) it.subject.isStickied else false }?.subject as Comment?

            val flow = DerapFlow(
                    stub,
                    callback,
                    composingFn,
                    replyFn,
                    _sentMessages,
                    _incomingMessages,
                    dispatcher.createNewEvent(ManuallyFetchedEvent::class, initValue, multiplexerProvider.get()),
                    conversationLinkage,
                    observationLinkage,
                    monitorFactory.get(),
                    conversationFactory.get(),
                    delayedDeleteFactory,
                    commentRef,
                    comment,
                    provideFlairCheck(initValue)
            )
            stub.setOuter(flow)
            flows.add(flow)
        }

        return flows
    }

}
