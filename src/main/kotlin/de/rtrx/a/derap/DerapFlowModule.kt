package de.rtrx.a.derap

import com.google.inject.Provides
import com.google.inject.Scopes
import com.google.inject.assistedinject.FactoryModuleBuilder
import com.google.inject.name.Names
import com.uchuhimo.konf.Config
import de.rtrx.a.database.*
import de.rtrx.a.flow.*
import de.rtrx.a.flow.events.comments.CommentsFetcherFactory
import de.rtrx.a.flow.events.comments.ManuallyFetchedEvent
import de.rtrx.a.derap.database.TIHIDDL
import de.rtrx.a.derap.database.TIHILinkage
import dev.misfitlabs.kotlinguice4.KotlinModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import net.dean.jraw.references.SubmissionReference
import javax.inject.Named

class DerapFlowModule(private val restart: Boolean) : KotlinModule(){

    @Provides
    fun provideDispatcherStub(
            newPosts: ReceiveChannel<SubmissionReference>,
            flowFactory: DerapFlowFactory,
            @Named("launcherScope") launcherScope: CoroutineScope,
            manuallyFetchedFactory: CommentsFetcherFactory
    ) : IFlowDispatcherStub<DerapFlow, DerapFlowFactory> = FlowDispatcherStub(newPosts, flowFactory, launcherScope,
            mapOf( ManuallyFetchedEvent::class to (manuallyFetchedFactory to SubmissionReference::class) ) as EventFactories)

    @Provides
    @Named("functions")
    fun provideDDLFunctions(config: Config) = with(DDL.Companion.Functions){
        listOf(
                addParentIfNotExists,
                TIHIDDL.createComment,
                commentWithMessage,
                TIHIDDL.createCheck,
                redditUsername
        ).map { it(config) }}

    @Provides
    @Named("tables")
    fun provideDDLTable(config: Config) = with(DDL.Companion.Tables) { listOf(
            submissions,
            TIHIDDL.commentsTable,
            relevantMessages,
            comments_caused,
            commentsHierarchy,
            check,
            unexScore,
            TIHIDDL.topPosts
    ).map { it(config) }}

    @Provides
    fun provideApprovedCheck(linkage: ObservationLinkage): DeletePrevention = DelayedDelete.approvedCheck(linkage)
    override fun configure() {
        install(FactoryModuleBuilder()
                .implement(DelayedDelete::class.java, RedditDelayedDelete::class.java)
                .build(DelayedDeleteFactory::class.java))

        bind(PostgresSQLinkage::class.java)

        bind(Linkage::class.java).to(TIHILinkage::class.java).`in`(Scopes.SINGLETON)
        bind(ObservationLinkage::class.java).to(TIHILinkage::class.java).`in`(Scopes.SINGLETON)
        bind(ConversationLinkage::class.java).to(TIHILinkage::class.java).`in`(Scopes.SINGLETON)


        bind(Boolean::class.java).annotatedWith(Names.named("restart")).toInstance(restart)
        bind(DerapFlowFactory::class.java).to(RedditDerapFlowFactory::class.java)
        bind(CoroutineScope::class.java).annotatedWith(Names.named("launcherScope"))
                .toInstance(CoroutineScope(Dispatchers.Default))
        bind(DerapFlowDispatcher::class.java)

    }
}